// expect: PANIC: uncaught java.lang.ArithmeticException: / by zero
package duke.panics;

import duke.kernel.Serial;

final class DivideByZero {
    static int zero() {
        return 0;
    }

    static void main() {
        Serial.init();
        int x = 1 / zero();
    }
}
