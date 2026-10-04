// expect: PANIC: uncaught java.lang.IllegalStateException: driver failed
// expect:   at duke.panics.UncaughtWithCause.start(UncaughtWithCause.java:22)
// expect:   at duke.panics.UncaughtWithCause.main(UncaughtWithCause.java:29)
// expect: Caused by: java.lang.ArithmeticException: / by zero
// expect:   at duke.panics.UncaughtWithCause.probe(UncaughtWithCause.java:15)
package duke.panics;

import duke.kernel.Serial;
import duke.kernel.x86.Idt;

final class UncaughtWithCause {
    static int zero;

    static int probe() {
        return 1 / zero; // PROBE
    }

    static void start() {
        try {
            probe();
        } catch (ArithmeticException e) {
            throw new IllegalStateException("driver failed", e); // START
        }
    }

    static void main() {
        Serial.init();
        Idt.load();
        start(); // MAIN
    }
}
