package duke.ktest;

import static duke.ktest.Assert.assertEquals;
import static duke.ktest.Assert.assertTrue;

import duke.kernel.virtio.VirtioBlock;

// tools/KernelTests.java attaches a 64-sector disk: sector n starts with "DUKEDISK" and n, then
// byte i is (n + i) & 0xFF.
final class VirtioBlockTest {

    private static VirtioBlock disk() {
        for (VirtioBlock disk : VirtioBlock.devices()) {
            if (disk.capacity() == 64) {
                return disk;
            }
        }
        throw new AssertionError("no 64-sector virtio disk among " + VirtioBlock.devices().size());
    }

    private static void assertSector(long n, byte[] buffer, int offset) {
        StringBuilder magic = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            magic.append((char) buffer[offset + i]);
        }
        assertEquals("DUKEDISK", magic.toString(), "sector " + n + " magic");
        long number = 0;
        for (int b = 7; b >= 0; b--) {
            number = number << 8 | (buffer[offset + 8 + b] & 0xFF);
        }
        assertEquals(n, number, "sector number");
        for (int i = 16; i < 512; i++) {
            if (buffer[offset + i] != (byte) (n + i)) {
                assertEquals((byte) (n + i), buffer[offset + i], "sector " + n + " byte " + i);
            }
        }
    }

    static void testFoundEveryDiskAndTheTestDiskIsWritable() {
        assertEquals(3, VirtioBlock.devices().size(), "the test disk and two FAT disks");
        assertTrue(!disk().readOnly(), "writable");
    }

    // Behind the harness's root port, so the scan found it by following a bridge.
    static void testFoundBehindABridge() {
        assertTrue(disk().function().bus != 0, "on bus " + disk().function().bus);
    }

    static void testReadsAKnownSector() {
        byte[] buffer = new byte[512];
        disk().read(37, buffer);
        assertSector(37, buffer, 0);
    }

    // 20 sectors take three requests through the 8-sector staging frame.
    static void testReadsAcrossRequests() {
        byte[] buffer = new byte[20 * 512];
        disk().read(5, buffer);
        for (int s = 0; s < 20; s++) {
            assertSector(5 + s, buffer, s * 512);
        }
    }

    static void testWritesAndReadsBack() {
        VirtioBlock disk = disk();
        byte[] out = new byte[2 * 512];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) (i * 31);
        }
        disk.write(60, out);
        byte[] in = new byte[2 * 512];
        disk.read(60, in);
        for (int i = 0; i < in.length; i++) {
            if (in[i] != out[i]) {
                assertEquals(out[i], in[i], "byte " + i);
            }
        }
        byte[] neighbour = new byte[512];
        disk.read(59, neighbour);
        assertSector(59, neighbour, 0);
    }

    static void testRejectsOutOfRangeAndPartialSectors() {
        VirtioBlock disk = disk();
        boolean threw = false;
        try {
            disk.read(63, new byte[2 * 512]);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        assertTrue(threw, "past the end");
        threw = false;
        try {
            disk.read(0, new byte[100]);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        assertTrue(threw, "partial sector");
    }
}
