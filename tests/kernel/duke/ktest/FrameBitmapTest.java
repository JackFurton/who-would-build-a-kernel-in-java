package duke.ktest;

import static duke.ktest.Assert.assertEquals;
import static duke.ktest.Assert.assertThrows;
import static duke.ktest.Assert.assertTrue;

import duke.kernel.mm.FrameBitmap;

final class FrameBitmapTest {

    static void testStartsFullyUsed() {
        FrameBitmap b = new FrameBitmap(100);
        assertEquals(0, b.freePages(), "free pages");
        assertEquals(-1, b.allocate(), "allocate with nothing free");
    }

    static void testAllocatesOnlyReleasedPages() {
        FrameBitmap b = new FrameBitmap(200);
        b.release(70, 5);
        for (int i = 0; i < 5; i++) {
            long page = b.allocate();
            assertTrue(page >= 70 && page < 75, "page " + page + " inside the released range");
        }
        assertEquals(-1, b.allocate(), "exhausted");
    }

    static void testLastPartialWordIsNeverHandedOut() {
        FrameBitmap b = new FrameBitmap(65);
        b.release(0, 65);
        int count = 0;
        while (b.allocate() >= 0) {
            count++;
        }
        assertEquals(65, count, "allocations from a 65-page bitmap");
    }

    static void testFreeThenReallocate() {
        FrameBitmap b = new FrameBitmap(128);
        b.release(0, 128);
        long a = b.allocate();
        b.free(a);
        assertEquals(128, b.freePages(), "free count restored");
        assertTrue(b.isFree(a), "page free again");
    }

    static void testDoubleFreeIsRejected() {
        FrameBitmap b = new FrameBitmap(64);
        b.release(10, 1);
        assertThrows(IllegalStateException.class, () -> b.free(10), "double free");
        assertThrows(IllegalArgumentException.class, () -> b.free(64), "out of range");
    }

    static void testNextFitWrapsAround() {
        FrameBitmap b = new FrameBitmap(256);
        b.release(0, 256);
        long first = b.allocate();
        for (int i = 1; i < 256; i++) {
            b.allocate();
        }
        b.free(first);
        assertEquals(first, b.allocate(), "wraps back to the freed page");
    }
}
