// expect: PANIC: ArrayStoreException: java.lang.Object into [Ljava.lang.String;
package duke.panics;

import duke.kernel.Serial;

final class ArrayStore {
    static void main() {
        Serial.init();
        Object[] a = new String[1];
        a[0] = new Object();
    }
}
