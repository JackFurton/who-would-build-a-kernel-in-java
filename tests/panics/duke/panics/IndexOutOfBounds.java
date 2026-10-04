// expect: PANIC: uncaught java.lang.ArrayIndexOutOfBoundsException: Index 3 out of bounds for length 3
// expect:   at duke.panics.IndexOutOfBounds.main(IndexOutOfBounds.java:11)
package duke.panics;

import duke.kernel.Serial;

final class IndexOutOfBounds {
    static void main() {
        Serial.init();
        int[] a = new int[3];
        a[3] = 1;
    }
}
