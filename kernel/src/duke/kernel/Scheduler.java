package duke.kernel;

import duke.kernel.mm.KernelStacks;
import duke.kernel.time.Timer;
import duke.rt.Magic;
import duke.rt.Tib;

/**
 * Kernel threads on one CPU, round-robin. The timer asks for a switch every tick, but a thread is
 * only ever switched out at a safepoint: a method prologue or a loop back-edge sees the request
 * (Magic.requestPreemption) and calls {@link #preempted}. Every frame of a parked thread therefore
 * sits at a call site with a stack map, and the collector scans parked stacks as precisely as the
 * running one.
 *
 * <p>All scheduler state is changed with interrupts off.
 */
public final class Scheduler {

    public static final int NEW = 0;
    public static final int RUNNABLE = 1;
    public static final int RUNNING = 2;
    public static final int SLEEPING = 3;
    public static final int JOINING = 4;
    public static final int DEAD = 5;

    private static final String[] STATE_NAMES = {"new", "runnable", "running", "sleeping", "joining", "dead"};
    private static final long INTERRUPT_FLAG = 1 << 9;

    /** One thread's scheduling state; java.lang.Thread holds one. */
    public static final class Task {
        final Thread thread;
        int state = NEW;
        /** Lowest address of the stack; the boot stack for main, which is never freed. */
        long stackBottom;
        long stackLimit;
        /** Where switchStack saves rsp; an array so its address is stable. */
        final long[] rsp = new long[1];
        /** False until the thread first runs, while its stack holds only the frame start() built. */
        boolean ran;
        long wakeTick;
        Task joining;
        Task next;

        public Task(Thread thread) {
            this.thread = thread;
        }
    }

    private static Task current;
    private static Task idle;
    private static Task runHead;
    private static Task runTail;
    /** Every live thread, including idle: the collector scans their stacks. */
    private static Task[] tasks;
    private static int taskCount;
    /** Exited, with its stack still to free: it couldn't free the stack it was running on. */
    private static Task zombie;

    private Scheduler() {
    }

    /** Adopts the boot thread as "main" and starts the idle thread. Interrupts must be off. */
    public static void init() {
        tasks = new Task[8];
        Task main = new Thread("main").kernelTask();
        main.stackBottom = Magic.stackBase();
        main.state = RUNNING;
        main.ran = true;
        current = main;
        add(main);
        idle = new Thread(Scheduler::idleLoop, "idle").kernelTask();
        if (!prepare(idle)) {
            Panic.panic("no stack for the idle thread");
        }
        add(idle);
    }

    /** From the timer interrupt, as its last act: anything after would see the request itself. */
    public static void tick() {
        if (current != null) {
            Magic.requestPreemption();
        }
    }

    public static Thread currentThread() {
        return current.thread;
    }

    public static void start(Task task) {
        if (task.state != NEW) {
            throw new IllegalThreadStateException();
        }
        long flags = Magic.flags();
        Magic.disableInterrupts();
        if (!prepare(task)) {
            restore(flags);
            throw new OutOfMemoryError("unable to create native thread: possibly out of memory or process/resource limits reached");
        }
        add(task);
        enqueue(task);
        restore(flags);
    }

    public static boolean isAlive(Task task) {
        return task.state != NEW && task.state != DEAD;
    }

    /** Runs another thread if one is ready, else returns at once. */
    public static void yield() {
        long flags = Magic.flags();
        Magic.disableInterrupts();
        wakeSleepers();
        if (runHead != null) {
            if (current != idle) {
                enqueue(current);
            }
            switchTo(dequeue());
        }
        restore(flags);
    }

    /** Called through Runtime.preempt at a safepoint after the timer asked for a switch. */
    public static void preempted() {
        if ((Magic.flags() & INTERRUPT_FLAG) != 0) {
            Scheduler.yield();
        }
    }

    /** Waits for something to happen: runs other threads if any are ready, else halts until an interrupt. */
    public static void pause() {
        long flags = Magic.flags();
        Magic.disableInterrupts();
        wakeSleepers();
        if (runHead != null) {
            enqueue(current);
            switchTo(dequeue());
            restore(flags);
            return;
        }
        // sti; hlt back to back: an interrupt can't slip in between and leave us halted past it.
        Magic.enableInterrupts();
        Magic.halt();
        restore(flags);
    }

    public static void sleep(long millis) {
        if (millis <= 0) {
            Scheduler.yield();
            return;
        }
        long flags = Magic.flags();
        Magic.disableInterrupts();
        current.wakeTick = Timer.ticks() + (millis * Timer.HZ + 999) / 1000;
        current.state = SLEEPING;
        switchTo(next());
        restore(flags);
    }

    public static void join(Task task) {
        long flags = Magic.flags();
        Magic.disableInterrupts();
        while (isAlive(task)) {
            current.joining = task;
            current.state = JOINING;
            switchTo(next());
        }
        restore(flags);
    }

    /** For the collector, which must not allocate: entries in {@link #parkedFrame}. */
    public static int taskCount() {
        return taskCount;
    }

    /**
     * The rbp of a parked thread's switchStack frame, whose return address is a call site with a
     * stack map; 0 for the running thread and for threads that haven't run yet.
     */
    public static long parkedFrame(int index) {
        Task task = tasks[index];
        if (task == current || !task.ran) {
            return 0;
        }
        return Magic.peekLong(task.rsp[0]);
    }

    /** One line per thread, for the shell. */
    public static void list() {
        for (int i = 0; i < taskCount; i++) {
            Task task = tasks[i];
            Console.println(task.thread.getName() + ": " + STATE_NAMES[task.state]);
        }
    }

    /**
     * Where a new thread's first switchStack returns to, with interrupts off and stack checks
     * disabled. Never returns: the frame start() built has nothing to return to.
     */
    static void threadMain() {
        Task self = current;
        Magic.setStackBase(self.stackBottom);
        Magic.resetStackLimit();
        reap();
        self.ran = true;
        Magic.enableInterrupts();
        try {
            self.thread.run();
        } catch (Throwable t) {
            Console.print("Exception in thread \"" + self.thread.getName() + "\" ");
            t.printStackTrace();
        }
        Magic.disableInterrupts();
        self.state = DEAD;
        remove(self);
        for (int i = 0; i < taskCount; i++) {
            Task waiter = tasks[i];
            if (waiter.state == JOINING && waiter.joining == self) {
                waiter.joining = null;
                enqueue(waiter);
            }
        }
        zombie = self;
        switchTo(next());
        Panic.panic("a dead thread was scheduled");
    }

    private static void idleLoop() {
        while (true) {
            Magic.disableInterrupts();
            wakeSleepers();
            if (runHead != null) {
                switchTo(dequeue());
                Magic.enableInterrupts();
            } else {
                Magic.enableInterrupts();
                Magic.halt();
            }
        }
    }

    /**
     * Builds the frame switchStack would have left on a fresh stack: the saved rbp points at an
     * rbp of 0 (the end of every stack walk) and threadMain as the return address, with a 0 return
     * address for threadMain itself above that.
     */
    private static boolean prepare(Task task) {
        long bottom = KernelStacks.allocate();
        if (bottom == -1) {
            return false;
        }
        long frame = bottom + KernelStacks.STACK_BYTES - 24;
        Magic.pokeLong(frame, 0);
        Magic.pokeLong(frame + 8, Magic.threadEntry());
        Magic.pokeLong(frame + 16, 0);
        Magic.pokeLong(frame - 8, frame);
        task.rsp[0] = frame - 8;
        task.stackBottom = bottom;
        task.state = RUNNABLE;
        return true;
    }

    /** Switches to {@code next} and returns once something switches back. Interrupts must be off. */
    private static void switchTo(Task next) {
        Task previous = current;
        if (next == previous) {
            previous.state = RUNNING;
            return;
        }
        Magic.cancelPreemption();
        previous.stackLimit = Magic.stackLimit();
        // Checks off until the new thread installs its own limit: ours means nothing on its stack.
        Magic.setStackLimit(0);
        if (previous.state == RUNNING) {
            // Only idle gets here: everyone else was queued, put to sleep or killed first.
            previous.state = RUNNABLE;
        }
        next.state = RUNNING;
        current = next;
        swap(Magic.addressOf(previous.rsp) + Tib.ARRAY_DATA, next.rsp[0]);
        Magic.setStackBase(current.stackBottom);
        Magic.setStackLimit(current.stackLimit);
        reap();
    }

    /** Only longs in this frame, so the collector can start a parked thread's walk at its caller. */
    private static void swap(long saveAt, long rsp) {
        Magic.switchStack(saveAt, rsp);
    }

    private static void reap() {
        if (zombie != null && zombie != current) {
            KernelStacks.free(zombie.stackBottom);
            zombie = null;
        }
    }

    private static Task next() {
        wakeSleepers();
        Task task = dequeue();
        return task != null ? task : idle;
    }

    private static void wakeSleepers() {
        long now = Timer.ticks();
        for (int i = 0; i < taskCount; i++) {
            Task task = tasks[i];
            if (task.state == SLEEPING && task.wakeTick <= now) {
                enqueue(task);
            }
        }
    }

    private static void enqueue(Task task) {
        task.state = RUNNABLE;
        task.next = null;
        if (runTail == null) {
            runHead = task;
        } else {
            runTail.next = task;
        }
        runTail = task;
    }

    private static Task dequeue() {
        Task task = runHead;
        if (task != null) {
            runHead = task.next;
            if (runHead == null) {
                runTail = null;
            }
            task.next = null;
        }
        return task;
    }

    private static void add(Task task) {
        if (taskCount == tasks.length) {
            Task[] grown = new Task[tasks.length * 2];
            for (int i = 0; i < taskCount; i++) {
                grown[i] = tasks[i];
            }
            tasks = grown;
        }
        tasks[taskCount++] = task;
    }

    private static void remove(Task task) {
        for (int i = 0; i < taskCount; i++) {
            if (tasks[i] == task) {
                tasks[i] = tasks[--taskCount];
                tasks[taskCount] = null;
                return;
            }
        }
    }

    private static void restore(long flags) {
        if ((flags & INTERRUPT_FLAG) != 0) {
            Magic.enableInterrupts();
        }
    }
}
