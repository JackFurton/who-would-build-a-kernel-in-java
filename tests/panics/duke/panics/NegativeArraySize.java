// expect: PANIC: uncaught java.lang.NegativeArraySizeException: -1
package duke.panics;

import duke.kernel.Serial;

final class NegativeArraySize {
    static void main() {
        Serial.init();
        int n = -1;
        int[] a = new int[n];
    }
}
