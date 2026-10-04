// expect: PANIC: uncaught java.lang.ArrayStoreException: java.lang.Object
package duke.panics;

import duke.kernel.Serial;

final class ArrayStore {
    static void main() {
        Serial.init();
        Object[] a = new String[1];
        a[0] = new Object();
    }
}
