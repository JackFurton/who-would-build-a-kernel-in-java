// expect: PANIC: ArrayIndexOutOfBoundsException (3, 3)
package duke.panics;

import duke.kernel.Serial;

final class IndexOutOfBounds {
    static void main() {
        Serial.init();
        int[] a = new int[3];
        a[3] = 1;
    }
}
