// expect: PANIC: uncaught java.lang.ArrayIndexOutOfBoundsException: Index -1 out of bounds for length 3
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
