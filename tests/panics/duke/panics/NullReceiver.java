// expect: PANIC: NullPointerException
package duke.panics;

import duke.kernel.Serial;

final class NullReceiver {
    static void main() {
        Serial.init();
        String s = null;
        s.length();
    }
}
