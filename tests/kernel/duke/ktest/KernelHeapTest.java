package duke.ktest;

import static duke.ktest.Assert.assertEquals;
import static duke.ktest.Assert.assertTrue;

import duke.kernel.mm.KernelAddressSpace;
import duke.kernel.mm.KernelHeap;
import duke.kernel.mm.PhysicalMemory;
import duke.rt.Heap;
import duke.rt.Magic;

final class KernelHeapTest {

    static void testNewObjectsComeFromTheGrowableRegion() {
        Object o = new Object();
        long address = Magic.addressOf(o);
        assertTrue(Heap.inGrowableRegion(o), "0x" + Long.toHexString(address) + " in the kernel heap region");
        assertTrue(address >= KernelHeap.BASE && address < KernelHeap.LIMIT, "inside the reserved range");
    }

    // More than the whole early arena, so the heap has to commit fresh frames repeatedly.
    static void testGrowsPastTheEarlyArena() {
        long committedBefore = Heap.committed();
        long framesBefore = PhysicalMemory.freeFrames();
        byte[][] chunks = new byte[24][];
        for (int i = 0; i < chunks.length; i++) {
            chunks[i] = new byte[1 << 20];
            chunks[i][chunks[i].length - 1] = (byte) i;
        }
        assertTrue(Heap.committed() - committedBefore >= 24L << 20, "committed at least 24 MiB more");
        assertTrue(framesBefore - PhysicalMemory.freeFrames() >= (24L << 20) / PhysicalMemory.PAGE_SIZE, "frames used");
        for (int i = 0; i < chunks.length; i++) {
            assertEquals(i, chunks[i][(1 << 20) - 1], "chunk " + i + " kept its data");
        }
    }

    static void testCommittedMemoryIsZeroed() {
        long[] big = new long[300_000];
        for (long v : big) {
            if (v != 0) {
                throw new AssertionError("non-zero word in a fresh array");
            }
        }
    }

    static void testHeapPagesAreWritableAndNotExecutable() {
        long flags = KernelAddressSpace.table().flags(Magic.addressOf(new int[1]));
        assertTrue((flags & duke.kernel.mm.PageTable.WRITABLE) != 0, "writable");
        if (KernelAddressSpace.noExecuteSupported()) {
            assertTrue((flags & duke.kernel.mm.PageTable.NO_EXECUTE) != 0, "NX");
        }
    }
}
