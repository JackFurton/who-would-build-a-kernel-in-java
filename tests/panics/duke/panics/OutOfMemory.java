// expect: PANIC: OutOfMemoryError: Java heap space
package duke.panics;

import duke.kernel.Serial;

final class OutOfMemory {
    static void main() {
        Serial.init();
        while (true) {
            byte[] chunk = new byte[1 << 20];
        }
    }
}
