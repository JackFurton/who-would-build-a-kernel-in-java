// expect: PANIC: uncaught java.lang.StackOverflowError
// expect:   at duke.panics.UncaughtStackOverflow.recurse(UncaughtStackOverflow.java:9)
package duke.panics;

import duke.kernel.Serial;

final class UncaughtStackOverflow {
    static int recurse(int n) {
        return recurse(n + 1) + 1;
    }

    static void main() {
        Serial.init();
        recurse(0);
    }
}
