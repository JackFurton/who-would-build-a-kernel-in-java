package duke.rt;

import duke.kernel.Panic;

/**
 * Entry points the compiler calls when generated code detects a fault. There are no exceptions
 * yet, so each of these is fatal.
 */
public final class Runtime {

    private Runtime() {
    }

    static void nullPointer() {
        Panic.panic("NullPointerException");
    }

    static void arrayIndexOutOfBounds(int index, int length) {
        Panic.panic("ArrayIndexOutOfBoundsException", index, length);
    }

    static void divideByZero() {
        Panic.panic("ArithmeticException: / by zero");
    }
}
