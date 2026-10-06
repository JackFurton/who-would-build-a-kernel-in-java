package duke.ktest;

import static duke.ktest.Assert.assertEquals;

import duke.kernel.mm.KernelAddressSpace;
import duke.kernel.mm.PageTable;
import duke.kernel.mm.PhysicalMemory;
import duke.rt.Magic;
import duke.rt.Tib;

final class CopyMemoryTest {

    private static byte pattern(int i) {
        return (byte) (37 * i + 11);
    }

    private static void checkCopy(byte[] buffer, int from, int to, int length) {
        for (int i = 0; i < buffer.length; i++) {
            buffer[i] = pattern(i);
        }
        long base = Magic.addressOf(buffer) + Tib.ARRAY_DATA;
        Magic.copyMemory(base + to, base + from, length);
        assertEquals(0, Magic.flags() & 0x400, "copy restores DF");
        for (int i = 0; i < buffer.length; i++) {
            int original = i >= to && i < to + length ? from + i - to : i;
            assertEquals(pattern(original), buffer[i], "copy and surrounding bytes");
        }
    }

    static void testCopyEveryAlignmentAndShortOverlap() {
        byte[] buffer = new byte[64];
        for (int from = 16; from < 24; from++) {
            for (int to = 16; to < 24; to++) {
                for (int length = 0; length <= 33; length++) {
                    checkCopy(buffer, from, to, length);
                }
            }
        }
    }

    static void testCopiesAcrossWordAndPageBoundaries() {
        byte[] buffer = new byte[8256];
        int[] lengths = {63, 64, 65, 4095, 4096, 4097};
        for (int length : lengths) {
            for (int offset = 0; offset < 8; offset++) {
                checkCopy(buffer, 8 + offset, 4136 - offset, length);
                checkCopy(buffer, 4136 - offset, 8 + offset, length);
                checkCopy(buffer, 16, 17 + offset, length);
                checkCopy(buffer, 17 + offset, 16, length);
            }
        }
    }

    static void testEmptyOperationsDoNotDereference() {
        Magic.copyMemory(0, 0, 0);
        Magic.copyMemory(0, 4096, 0);
        Magic.copyMemory(4096, 0, 0);
        assertEquals(0, Magic.flags() & 0x400, "empty operations leave DF clear");
    }

    // Two pages with unmapped neighbors catch even reads outside the requested span.
    static void testExactSpansNextToUnmappedPages() {
        long source = 0xffff_c000_4000_0000L;
        long destination = source + 8192;
        PageTable table = KernelAddressSpace.table();
        long sourceFrame = PhysicalMemory.allocateZeroed();
        long destinationFrame = PhysicalMemory.allocateZeroed();
        table.map(source, sourceFrame, PageTable.WRITABLE);
        table.map(destination, destinationFrame, PageTable.WRITABLE);
        try {
            for (int i = 0; i < 4096; i++) {
                Magic.pokeByte(source + i, pattern(i));
            }
            int[] lengths = {1, 7, 8, 9, 15, 16, 17, 4095, 4096};
            for (int length : lengths) {
                long offset = 4096 - length;
                Magic.copyMemory(destination + offset, source + offset, length);
                for (int i = 0; i < length; i++) {
                    assertEquals(pattern((int) offset + i), Magic.peekByte(destination + offset + i), "page end copy");
                }
            }
            // Backward overlap must not read before the first page or after the last byte.
            Magic.copyMemory(source + 1, source, 4095);
            for (int i = 1; i < 4096; i++) {
                assertEquals(pattern(i - 1), Magic.peekByte(source + i), "page-wide backward copy");
            }
        } finally {
            table.unmap(source);
            table.unmap(destination);
            PhysicalMemory.free(sourceFrame);
            PhysicalMemory.free(destinationFrame);
        }
    }
}
