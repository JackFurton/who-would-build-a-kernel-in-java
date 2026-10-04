// expect: PANIC: uncaught java.lang.NullPointerException
// expect:   at duke.panics.NullReceiver.main(NullReceiver.java:11)
package duke.panics;

import duke.kernel.Serial;

final class NullReceiver {
    static void main() {
        Serial.init();
        String s = null;
        s.length();
    }
}
