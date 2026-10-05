package duke.ktest;

import static duke.ktest.Assert.assertEquals;

import duke.rt.Magic;

final class ZeroMemoryWordsTest {

    static void testExactSpanIncludingPageBoundaries() {
        int[] counts = {0, 1, 2, 7, 8, 9, 63, 64, 65, 511, 512, 513};
        long[] buffer = new long[520];
        for (int count : counts) {
            for (int i = 0; i < buffer.length; i++) {
                buffer[i] = -1L;
            }
            Magic.zeroMemoryWords(Magic.addressOf(buffer) + 16 + 24, count);
            for (int i = 0; i < buffer.length; i++) {
                assertEquals(i >= 3 && i < 3 + count ? 0L : -1L, buffer[i], "exact zero span");
            }
        }
    }

    static void testZeroLengthDoesNotDereference() {
        Magic.zeroMemoryWords(0, 0);
    }
}
