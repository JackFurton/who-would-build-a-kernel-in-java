package duke.ktest;

import static duke.ktest.Assert.assertEquals;
import static duke.ktest.Assert.assertTrue;

import duke.rt.Heap;

final class CollectorClearingTest {

    private static byte[] transientRoot;

    // The allocating frame is gone before the next collection: only the static can retain this.
    private static long markTemporaryRoot() {
        transientRoot = new byte[1 << 20];
        transientRoot[transientRoot.length - 1] = 42;
        System.gc();
        assertEquals(42, transientRoot[transientRoot.length - 1], "marked root remains live");
        return Heap.lastLive();
    }

    static void testMarkBitsDoNotRetainDroppedRoots() {
        for (int round = 0; round < 3; round++) {
            long withRoot = markTemporaryRoot();
            transientRoot = null;
            System.gc();
            assertTrue(Heap.lastLive() <= withRoot - (1 << 20), "old marks do not keep the dropped array alive");
        }
    }

    static void testAllocationsRemainZeroedAfterRepeatedCollections() {
        Object[] survivors = new Object[32];
        for (int round = 0; round < 4; round++) {
            for (int i = 0; i < 512; i++) {
                long[] data = new long[129];
                for (int j = 0; j < data.length; j++) {
                    assertEquals(0, data[j], "fresh word after collection");
                    data[j] = -1L;
                }
                if (i % 16 == 0) {
                    survivors[i / 16] = data;
                }
            }
            System.gc();
            for (Object survivor : survivors) {
                assertEquals(-1L, ((long[]) survivor)[128], "live arrays beside holes remain intact");
            }
        }
    }
}
