package duke.kernel;

import duke.kernel.mm.KernelStacks;
import duke.kernel.time.Timer;
import duke.rt.Magic;
import duke.rt.Tib;

/**
 * Kernel threads, round-robin. The timer asks for a switch every tick, but a thread is only ever
 * switched out at a safepoint: a method prologue or a loop back-edge sees the request
 * (Magic.requestPreemption) and calls {@link #preempted}. Every frame of a parked thread therefore
 * sits at a call site with a stack map, and the collector scans parked stacks as precisely as the
 * running one.
 *
 * <p>Every CPU has an idle thread, but only one CPU at a time, the runner, runs the others: the
 * heap, the collector and class initialization still assume Java runs on one CPU. A CPU becomes
 * the runner by taking a thread off the queue while no CPU is, and stops being it when it goes
 * back to its idle thread. Idle loops allocate nothing, so the collector, which only ever runs on
 * the runner, can skip the stacks of the idle threads running elsewhere.
 *
 * <p>All scheduler state is guarded by {@link #lock}, which also turns interrupts off. A switch
 * hands the lock over: the thread switched to is the one that releases it.
 */
public final class Scheduler {

    public static final int NEW = 0;
    public static final int RUNNABLE = 1;
    public static final int RUNNING = 2;
    public static final int SLEEPING = 3;
    public static final int JOINING = 4;
    public static final int DEAD = 5;
    /** Waiting to enter a monitor. */
    public static final int BLOCKED = 6;
    /** In Object.wait, untimed if wakeTick is 0. */
    public static final int WAITING = 7;

    private static final String[] STATE_NAMES = {
        "new", "runnable", "running", "sleeping", "joining", "dead", "blocked", "waiting"};
    private static final long INTERRUPT_FLAG = 1 << 9;
    private static final long MAIN_ID = 1;
    private static final int NO_CPU = -1;

    /** One thread's scheduling state; java.lang.Thread holds one. */
    public static final class Task {
        final Thread thread;
        /** 1 for main; nothing reuses an id. */
        final long id = ++lastId;
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
        /** The monitor this thread is blocked or waiting on, and the count it holds it with. */
        Monitors.Monitor monitor;
        int monitorCount;
        /** Run queue, or a monitor's blocked or waiting list: a thread is on at most one. */
        Task next;

        public Task(Thread thread) {
            this.thread = thread;
        }
    }

    private static final int[] LOCK = new int[1];
    private static final Task[] CURRENT = new Task[Smp.MAX_CPUS];
    private static final Task[] IDLE = new Task[Smp.MAX_CPUS];
    /** The CPU running threads other than idle ones, or NO_CPU. */
    private static int runner = NO_CPU;
    private static long lastId;
    private static Task runHead;
    private static Task runTail;
    /** Every live thread, including the idle ones: the collector scans their stacks. */
    private static Task[] tasks;
    private static int taskCount;
    /** Exited, with its stack still to free: it couldn't free the stack it was running on. */
    private static Task zombie;

    private Scheduler() {
    }

    /**
     * Adopts the boot thread as "main" and starts the boot CPU's idle thread. Interrupts must be
     * off, and the other CPUs not started yet.
     */
    public static void init() {
        tasks = new Task[8];
        Task main = new Thread("main").kernelTask();
        main.stackBottom = Magic.stackBase();
        main.state = RUNNING;
        main.ran = true;
        CURRENT[0] = main;
        runner = 0;
        add(main);
        Task idle = new Thread(Scheduler::idleLoop, "idle 0").kernelTask();
        if (!prepare(idle)) {
            Panic.panic("no stack for the idle thread");
        }
        IDLE[0] = idle;
        add(idle);
    }

    /** From Smp.start, on the boot CPU: the idle thread for another CPU, on the stack it starts on. */
    static void addIdle(int cpu, long stackBottom) {
        // Not running until the CPU gets here; one that never checks in never does.
        Task idle = new Thread(Scheduler::idleLoop, "idle " + cpu).kernelTask();
        idle.stackBottom = stackBottom;
        IDLE[cpu] = idle;
        long flags = lock();
        add(idle);
        unlock(flags);
    }

    /** Where another CPU ends up once it's set up, as the idle thread addIdle made for it. */
    static void enterIdle() {
        int cpu = Magic.cpuIndex();
        Task idle = IDLE[cpu];
        idle.state = RUNNING;
        CURRENT[cpu] = idle;
        idle.ran = true;
        idleLoop();
    }

    /** Takes the scheduler lock and turns interrupts off; returns the flags to give {@link #unlock}. */
    static long lock() {
        long flags = Magic.flags();
        Magic.disableInterrupts();
        long word = Magic.addressOf(LOCK) + Tib.ARRAY_DATA;
        while (Magic.exchangeInt(word, 1) != 0) {
            while (LOCK[0] != 0) {
                Magic.pause();
            }
        }
        return flags;
    }

    /** Releases the lock and turns interrupts back on if {@code flags} had them on. */
    static void unlock(long flags) {
        LOCK[0] = 0;
        if ((flags & INTERRUPT_FLAG) != 0) {
            Magic.enableInterrupts();
        }
    }

    /** From the timer interrupt, as its last act: anything after would see the request itself. */
    public static void tick() {
        if (current() != null) {
            Magic.requestPreemption();
        }
    }

    public static Thread currentThread() {
        return current().thread;
    }

    /** Null before init. No safepoint between reading the CPU and its entry, so no migrating between. */
    static Task current() {
        return CURRENT[Magic.cpuIndex()];
    }

    /** The running thread's id; main's before init, since only main runs then. */
    public static long currentId() {
        Task current = current();
        return current == null ? MAIN_ID : current.id;
    }

    public static boolean isAlive(long id) {
        for (int i = 0; i < taskCount; i++) {
            if (tasks[i].id == id) {
                return true;
            }
        }
        return false;
    }

    public static void start(Task task) {
        if (task.state != NEW) {
            throw new IllegalThreadStateException();
        }
        long flags = lock();
        if (!prepare(task)) {
            unlock(flags);
            throw new OutOfMemoryError("unable to create native thread: possibly out of memory or process/resource limits reached");
        }
        add(task);
        enqueue(task);
        unlock(flags);
    }

    public static boolean isAlive(Task task) {
        return task.state != NEW && task.state != DEAD;
    }

    /** Runs another thread if one is ready, else returns at once. */
    public static void yield() {
        long flags = lock();
        wakeSleepers();
        Task next = takeRunnable();
        if (next != null) {
            Task self = current();
            if (self != IDLE[Magic.cpuIndex()]) {
                enqueue(self);
            }
            switchTo(next);
        }
        unlock(flags);
    }

    /** Called through Runtime.preempt at a safepoint after the timer asked for a switch. */
    public static void preempted() {
        if ((Magic.flags() & INTERRUPT_FLAG) != 0) {
            Scheduler.yield();
        }
    }

    /**
     * Waits for something to happen: runs other threads if any are ready, else halts until an
     * interrupt. Still the runner while halted, since this thread is still running here.
     */
    public static void pause() {
        long flags = lock();
        wakeSleepers();
        Task next = takeRunnable();
        if (next != null) {
            enqueue(current());
            switchTo(next);
            unlock(flags);
            return;
        }
        LOCK[0] = 0;
        // sti; hlt back to back: an interrupt can't slip in between and leave us halted past it.
        Magic.enableInterrupts();
        Magic.halt();
        if ((flags & INTERRUPT_FLAG) == 0) {
            Magic.disableInterrupts();
        }
    }

    public static void sleep(long millis) {
        if (millis <= 0) {
            Scheduler.yield();
            return;
        }
        long flags = lock();
        Task self = current();
        self.wakeTick = Timer.ticks() + (millis * Timer.HZ + 999) / 1000;
        self.state = SLEEPING;
        switchTo(next());
        unlock(flags);
    }

    public static void join(Task task) {
        long flags = lock();
        while (isAlive(task)) {
            // Re-read after every switch: this thread may come back on another CPU.
            Task self = current();
            self.joining = task;
            self.state = JOINING;
            switchTo(next());
        }
        unlock(flags);
    }

    /** For the collector, which must not allocate: entries in {@link #parkedFrame}. */
    public static int taskCount() {
        return taskCount;
    }

    /**
     * The rbp of a parked thread's switchStack frame, whose return address is a call site with a
     * stack map; 0 for threads running on any CPU and for threads that haven't run yet.
     */
    public static long parkedFrame(int index) {
        Task task = tasks[index];
        if (!task.ran || running(task)) {
            return 0;
        }
        return Magic.peekLong(task.rsp[0]);
    }

    private static boolean running(Task task) {
        for (int cpu = 0; cpu < Smp.cpuCount(); cpu++) {
            if (CURRENT[cpu] == task) {
                return true;
            }
        }
        return false;
    }

    /** One line per thread, for the shell. */
    public static void list() {
        for (int i = 0; i < taskCount; i++) {
            Task task = tasks[i];
            String where = "";
            for (int cpu = 0; cpu < Smp.cpuCount(); cpu++) {
                if (CURRENT[cpu] == task) {
                    where = " on cpu " + cpu;
                }
            }
            Console.println(task.thread.getName() + ": " + STATE_NAMES[task.state] + where);
        }
    }

    /**
     * Where a new thread's first switchStack returns to, holding the lock with interrupts off and
     * stack checks disabled. Never returns: the frame start() built has nothing to return to.
     */
    static void threadMain() {
        Task self = current();
        Magic.setStackBase(self.stackBottom);
        Magic.resetStackLimit();
        reap();
        self.ran = true;
        unlock(INTERRUPT_FLAG);
        try {
            self.thread.run();
        } catch (Throwable t) {
            Console.print("Exception in thread \"" + self.thread.getName() + "\" ");
            t.printStackTrace();
        }
        lock();
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
            lock();
            wakeSleepers();
            Task next = takeRunnable();
            if (next != null) {
                switchTo(next);
                unlock(INTERRUPT_FLAG);
            } else {
                LOCK[0] = 0;
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

    /**
     * Switches to {@code next} and returns once something switches back, maybe on another CPU.
     * The lock must be held.
     */
    static void switchTo(Task next) {
        int cpu = Magic.cpuIndex();
        Task previous = CURRENT[cpu];
        if (next == previous) {
            previous.state = RUNNING;
            return;
        }
        if (next == IDLE[cpu]) {
            runner = NO_CPU;
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
        CURRENT[cpu] = next;
        swap(Magic.addressOf(previous.rsp) + Tib.ARRAY_DATA, next.rsp[0]);
        Task self = current();
        Magic.setStackBase(self.stackBottom);
        Magic.setStackLimit(self.stackLimit);
        reap();
    }

    /** Only longs in this frame, so the collector can start a parked thread's walk at its caller. */
    private static void swap(long saveAt, long rsp) {
        Magic.switchStack(saveAt, rsp);
    }

    private static void reap() {
        if (zombie != null && zombie != current()) {
            KernelStacks.free(zombie.stackBottom);
            zombie = null;
        }
    }

    /** What a thread that can't go on should switch to: a queued thread, or this CPU's idle thread. */
    static Task next() {
        wakeSleepers();
        Task task = takeRunnable();
        return task != null ? task : IDLE[Magic.cpuIndex()];
    }

    /** A queued thread, if this CPU is the runner or can become it; else null. */
    private static Task takeRunnable() {
        if (runHead == null) {
            return null;
        }
        int cpu = Magic.cpuIndex();
        if (runner != cpu) {
            if (runner != NO_CPU) {
                return null;
            }
            runner = cpu;
        }
        return dequeue();
    }

    private static void wakeSleepers() {
        long now = Timer.ticks();
        for (int i = 0; i < taskCount; i++) {
            Task task = tasks[i];
            if (task.state == SLEEPING && task.wakeTick <= now) {
                enqueue(task);
            } else if (task.state == WAITING && task.wakeTick != 0 && task.wakeTick <= now) {
                Monitors.timedOut(task);
            }
        }
    }

    static void enqueue(Task task) {
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

}
