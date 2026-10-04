// expect: PANIC: uncaught java.lang.ArithmeticException: / by zero
// expect:   at duke.panics.DeepTrace.inner(DeepTrace.java:15)
// expect:   at duke.panics.DeepTrace.middle(DeepTrace.java:19)
// expect:   at duke.panics.DeepTrace.outer(DeepTrace.java:23)
// expect:   at duke.panics.DeepTrace.main(DeepTrace.java:28)
package duke.panics;

import duke.kernel.Serial;

final class DeepTrace {
    static int zero;

    static int inner(int x) {
        // Division sits on this line.
        return x / zero;
    }

    static int middle(int x) {
        return inner(x + 1) * 2;
    }

    static int outer() {
        return middle(1);
    }

    static void main() {
        Serial.init();
        outer();
    }
}
