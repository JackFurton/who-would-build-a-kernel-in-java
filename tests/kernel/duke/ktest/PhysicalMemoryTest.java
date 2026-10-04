package duke.ktest;

import static duke.ktest.Assert.assertEquals;
import static duke.ktest.Assert.assertThrows;
import static duke.ktest.Assert.assertTrue;

import duke.boot.Limine;
import duke.kernel.mm.PhysicalMemory;
import duke.rt.Magic;

final class PhysicalMemoryTest {

    private static boolean insideUsableRegion(long frame) {
        for (int i = 0; i < Limine.memoryMapSize(); i++) {
            long base = Limine.memoryMapBase(i);
            if (Limine.memoryMapType(i) == Limine.MEMMAP_USABLE && frame >= base
                    && frame + PhysicalMemory.PAGE_SIZE <= base + Limine.memoryMapLength(i)) {
                return true;
            }
        }
        return false;
    }

    static void testFramesAreAlignedUsableAndDistinct() {
        long[] frames = new long[256];
        for (int i = 0; i < frames.length; i++) {
            frames[i] = PhysicalMemory.allocate();
            assertEquals(0, frames[i] % PhysicalMemory.PAGE_SIZE, "aligned");
            assertTrue(insideUsableRegion(frames[i]), "frame 0x" + Long.toHexString(frames[i]) + " is usable memory");
            for (int j = 0; j < i; j++) {
                assertTrue(frames[i] != frames[j], "distinct");
            }
        }
        for (long frame : frames) {
            PhysicalMemory.free(frame);
        }
    }

    static void testZeroedFrameIsZeroThroughTheDirectMap() {
        long dirty = PhysicalMemory.allocate();
        Magic.fillMemory(PhysicalMemory.toVirtual(dirty), 0xAB, PhysicalMemory.PAGE_SIZE);
        PhysicalMemory.free(dirty);
        long frame = PhysicalMemory.allocateZeroed();
        long virtual = PhysicalMemory.toVirtual(frame);
        for (long offset = 0; offset < PhysicalMemory.PAGE_SIZE; offset += 8) {
            assertEquals(0, Magic.peekLong(virtual + offset), "word at " + offset);
        }
        PhysicalMemory.free(frame);
    }

    static void testExhaustAndRefill() {
        long before = PhysicalMemory.freeFrames();
        long[] all = new long[(int) before];
        for (int i = 0; i < all.length; i++) {
            all[i] = PhysicalMemory.allocate();
        }
        assertEquals(0, PhysicalMemory.freeFrames(), "all allocated");
        assertThrows(OutOfMemoryError.class, PhysicalMemory::allocate, "nothing left");
        for (long frame : all) {
            PhysicalMemory.free(frame);
        }
        assertEquals(before, PhysicalMemory.freeFrames(), "all returned");
    }

    static void testMisalignedFreeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> PhysicalMemory.free(4097), "misaligned");
    }
}
