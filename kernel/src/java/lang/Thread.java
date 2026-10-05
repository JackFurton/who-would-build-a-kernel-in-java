package java.lang;

import duke.kernel.Scheduler;

/** A kernel thread; duke.kernel.Scheduler does the work. No priorities, daemons or interrupts yet. */
public class Thread implements Runnable {

    private static int threadNumber;

    private final Runnable task;
    private final String name;
    private final Scheduler.Task kernelTask;

    public Thread() {
        this(null, "Thread-" + threadNumber++);
    }

    public Thread(Runnable task) {
        this(task, "Thread-" + threadNumber++);
    }

    public Thread(String name) {
        this(null, name);
    }

    public Thread(Runnable task, String name) {
        if (name == null) {
            throw new NullPointerException("'name' is null");
        }
        this.task = task;
        this.name = name;
        this.kernelTask = new Scheduler.Task(this);
    }

    public static Thread currentThread() {
        return Scheduler.currentThread();
    }

    public static void sleep(long millis) throws InterruptedException {
        if (millis < 0) {
            throw new IllegalArgumentException("timeout value is negative");
        }
        Scheduler.sleep(millis);
    }

    public static void yield() {
        Scheduler.yield();
    }

    public void start() {
        Scheduler.start(kernelTask);
    }

    @Override
    public void run() {
        if (task != null) {
            task.run();
        }
    }

    public final void join() throws InterruptedException {
        Scheduler.join(kernelTask);
    }

    public final boolean isAlive() {
        return Scheduler.isAlive(kernelTask);
    }

    public final String getName() {
        return name;
    }

    /** The scheduler's record of this thread. Not JDK API. */
    public final Scheduler.Task kernelTask() {
        return kernelTask;
    }
}
