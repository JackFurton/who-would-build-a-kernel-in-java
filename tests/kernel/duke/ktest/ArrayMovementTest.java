package duke.ktest;

import static duke.ktest.Assert.assertEquals;
import static duke.ktest.Assert.assertTrue;

import duke.js.rt.JsArray;

final class ArrayMovementTest {

    static void testJavaScriptArrayCopiesAndShiftsKeepReferences() {
        Object[] original = new Object[65];
        for (int i = 0; i < original.length; i++) {
            original[i] = new Object();
        }
        JsArray array = new JsArray(original);
        array.set(0, "changed");
        assertTrue(original[0] != array.get(0), "constructor copies its input");
        array.set(0, original[0]);
        Object first = new Object();
        array.addFirst(first); // Also grows the full backing array.
        System.gc();
        assertTrue(array.removeFirst() == first, "unshifted value survives collection");
        for (int i = 0; i < original.length; i++) {
            assertTrue(array.removeFirst() == original[i], "shift preserves order and identity");
        }
        assertEquals(0, array.length(), "all shifted out");
        assertTrue(array.removeFirst() == null, "empty shift");
        array.setLength(65);
        for (int i = 0; i < array.length(); i++) {
            assertTrue(array.get(i) == null, "removed references do not reappear");
        }
    }

    static void testJavaScriptArrayRepeatedUnshift() {
        JsArray array = new JsArray();
        for (int i = 0; i < 65; i++) {
            array.addFirst(Integer.valueOf(i));
        }
        System.gc();
        for (int i = 0; i < 65; i++) {
            assertEquals(i, ((Integer) array.removeLast()).intValue(), "unshift across repeated growth");
        }
        assertTrue(array.removeLast() == null, "empty pop");
    }
}
