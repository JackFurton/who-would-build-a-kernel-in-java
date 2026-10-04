package duke.ktest;

import static duke.ktest.Assert.assertEquals;
import static duke.ktest.Assert.assertTrue;

import duke.boot.Limine;
import duke.kernel.mm.KernelAddressSpace;
import duke.kernel.mm.PageTable;
import duke.kernel.mm.PhysicalMemory;
import duke.kernel.x86.Interrupts;
import duke.rt.Magic;

final class PagingTest {

    /** A spare 1 GiB slot of the higher half, outside the image and the direct map. */
    private static final long SCRATCH = 0xffff_c000_0000_0000L;

    private static long imageToPhysical(long virtual) {
        return virtual - Limine.kernelVirtualBase() + Limine.kernelPhysicalBase();
    }

    static void testRunningOnOurTable() {
        assertEquals(KernelAddressSpace.table().root(), Magic.readCr3() & ~0xFFFL, "CR3");
    }

    static void testImageTranslatesToWhereLimineLoadedIt() {
        long code = Magic.peekLong(Magic.interruptStubs());
        long table = Magic.methodTable();
        long object = Magic.addressOf(new long[4]);
        PageTable t = KernelAddressSpace.table();
        assertEquals(imageToPhysical(code), t.translate(code), "text");
        assertEquals(imageToPhysical(table), t.translate(table), "rodata");
        assertEquals(imageToPhysical(object), t.translate(object), "heap arena in bss");
    }

    static void testSectionPermissions() {
        PageTable t = KernelAddressSpace.table();
        long nx = KernelAddressSpace.noExecuteSupported() ? PageTable.NO_EXECUTE : 0;
        long code = t.flags(Magic.peekLong(Magic.interruptStubs()));
        long rodata = t.flags(Magic.methodTable());
        long data = t.flags(Magic.addressOf(new int[1]));
        assertEquals(0, code & (PageTable.WRITABLE | PageTable.NO_EXECUTE), "text is read-only and executable");
        assertEquals(nx, rodata & (PageTable.WRITABLE | PageTable.NO_EXECUTE), "rodata is read-only, not executable");
        assertEquals(PageTable.WRITABLE | nx, data & (PageTable.WRITABLE | PageTable.NO_EXECUTE), "data is writable, not executable");
    }

    static void testDirectMapCoversFreshFrames() {
        long frame = PhysicalMemory.allocate();
        assertEquals(frame, KernelAddressSpace.table().translate(PhysicalMemory.toVirtual(frame)), "hhdm");
        PhysicalMemory.free(frame);
    }

    static void testMapWriteUnmap() {
        PageTable t = KernelAddressSpace.table();
        long frame = PhysicalMemory.allocateZeroed();
        t.map(SCRATCH, frame, PageTable.WRITABLE);
        Magic.pokeLong(SCRATCH + 8, 0x1234_5678_9abcL);
        assertEquals(0x1234_5678_9abcL, Magic.peekLong(PhysicalMemory.toVirtual(frame) + 8), "same frame via hhdm");
        t.unmap(SCRATCH);
        assertEquals(-1, t.translate(SCRATCH), "unmapped");
        PhysicalMemory.free(frame);
    }

    private static int demandFaults;

    // A Java page-fault handler maps a zeroed frame and returns; the CPU retries the access.
    static void testDemandPagingThroughPageFaultHandler() {
        PageTable t = KernelAddressSpace.table();
        long page = SCRATCH + 0x10_0000;
        Interrupts.register(14, frame -> {
            long address = Magic.readCr2();
            if ((address & -PhysicalMemory.PAGE_SIZE) != page) {
                throw new IllegalStateException("unexpected fault at 0x" + Long.toHexString(address));
            }
            demandFaults++;
            t.map(page, PhysicalMemory.allocateZeroed(), PageTable.WRITABLE);
        });
        try {
            assertEquals(0, Magic.peekLong(page + 64), "fresh page reads as zero");
            Magic.pokeLong(page + 64, 77);
            assertEquals(77, Magic.peekLong(page + 64), "and keeps writes");
            assertEquals(1, demandFaults, "exactly one fault");
        } finally {
            Interrupts.register(14, null);
            long frame = t.translate(page);
            t.unmap(page);
            PhysicalMemory.free(frame);
        }
    }
}
