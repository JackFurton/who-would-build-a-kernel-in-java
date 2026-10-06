package duke.ktest;

import static duke.ktest.Assert.assertEquals;

import duke.kernel.mm.KernelAddressSpace;
import duke.kernel.mm.PageTable;
import duke.kernel.mm.PhysicalMemory;
import duke.rt.Magic;
import duke.rt.Tib;

final class FillMemoryTest {

    private static byte pattern(int i) {
        return (byte) (37 * i + 11);
    }

    static void testFillEveryAlignmentLengthAndLowByte() {
        byte[] buffer = new byte[56];
        int[] values = {0, 0xa5, -1, 0x1234};
        for (int value : values) {
            for (int offset = 8; offset < 16; offset++) {
                for (int length = 0; length <= 33; length++) {
                    for (int i = 0; i < buffer.length; i++) {
                        buffer[i] = pattern(i);
                    }
                    Magic.fillMemory(Magic.addressOf(buffer) + Tib.ARRAY_DATA + offset, value, length);
                    for (int i = 0; i < buffer.length; i++) {
                        byte expected = i >= offset && i < offset + length ? (byte) value : pattern(i);
                        assertEquals(expected, buffer[i], "fill and surrounding bytes");
                    }
                }
            }
        }
    }

    static void testZeroLengthDoesNotDereference() {
        Magic.fillMemory(0, -1, 0);
    }

    static void testFillStopsAtUnmappedPage() {
        long destination = 0xffff_c000_4000_0000L;
        PageTable table = KernelAddressSpace.table();
        long frame = PhysicalMemory.allocateZeroed();
        table.map(destination, frame, PageTable.WRITABLE);
        try {
            int[] lengths = {1, 7, 8, 9, 15, 16, 17, 4095, 4096};
            for (int length : lengths) {
                for (int i = 0; i < 4096; i++) {
                    Magic.pokeByte(destination + i, (byte) 0x5a);
                }
                long offset = 4096 - length;
                Magic.fillMemory(destination + offset, 0x1a5, length);
                for (int i = 0; i < 4096; i++) {
                    byte expected = i < offset ? (byte) 0x5a : (byte) 0xa5;
                    assertEquals(expected, Magic.peekByte(destination + i), "page end fill and left canary");
                }
            }
        } finally {
            table.unmap(destination);
            PhysicalMemory.free(frame);
        }
    }
}
