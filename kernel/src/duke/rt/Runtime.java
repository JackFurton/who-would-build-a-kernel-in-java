package duke.rt;

import duke.kernel.Monitors;
import duke.kernel.Panic;
import duke.kernel.Scheduler;
import duke.kernel.Smp;

/** Entry points the compiler calls inline when a runtime check fails. Messages match HotSpot's. */
public final class Runtime {

    /** Compiler.INITIALIZED. */
    private static final int INITIALIZED = 1;

    private Runtime() {
    }

    static void nullPointer() {
        throw new NullPointerException();
    }

    static void arrayIndexOutOfBounds(int index, int length) {
        throw new ArrayIndexOutOfBoundsException("Index " + index + " out of bounds for length " + length);
    }

    static void stackOverflow() {
        throw new StackOverflowError();
    }

    static long currentThreadId() {
        return Scheduler.currentId();
    }

    /**
     * A class initializer stub found the class being initialized: by this thread, which carries on,
     * or by another, which this thread waits out. An owner of 0 means the initializing thread
     * hasn't recorded itself yet. If it died mid-initializer there's nothing left to wait for.
     */
    static void awaitInitialization(long flag, long owner) {
        long self = Scheduler.currentId();
        while (Magic.peekByte(flag) != INITIALIZED) {
            long initializer = Magic.peekLong(owner);
            if (initializer == self || initializer != 0 && !Scheduler.isAlive(initializer)) {
                return;
            }
            Scheduler.pause();
        }
    }

    static void monitorEnter(Object object) {
        Monitors.enter(object);
    }

    static void monitorExit(Object object) {
        Monitors.exit(object);
    }

    /** A prologue or loop back-edge found a preemption request (Magic.requestPreemption). */
    static void preempt() {
        if (!Heap.allocating()) {
            Smp.stopIfRequested();
            Scheduler.preempted();
        }
    }

    static void stackExhausted() {
        Panic.panic("StackOverflowError: stack exhausted while handling a stack overflow");
    }

    static void divideByZero() {
        throw new ArithmeticException("/ by zero");
    }
}
