// expect: PANIC: ArrayIndexOutOfBoundsException (-1, 3)
package duke.panics;

import duke.kernel.Serial;

final class NegativeIndex {
    static void main() {
        Serial.init();
        int[] a = new int[3];
        int i = -1;
        a[i] = 1;
    }
}
