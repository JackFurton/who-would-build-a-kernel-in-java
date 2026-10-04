package duke.rt;

import duke.kernel.Panic;

/** Entry points the compiler calls inline when a runtime check fails. Messages match HotSpot's. */
public final class Runtime {

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

    static void stackExhausted() {
        Panic.panic("StackOverflowError: stack exhausted while handling a stack overflow");
    }

    static void divideByZero() {
        throw new ArithmeticException("/ by zero");
    }
}
