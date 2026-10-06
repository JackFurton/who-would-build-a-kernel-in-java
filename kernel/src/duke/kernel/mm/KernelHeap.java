package duke.kernel.mm;

import duke.rt.Heap;

/** Moves the Java heap off the early .bss arena into a region backed by frames on demand. */
public final class KernelHeap {

    /** 512 GiB of virtual space in its own PML4 slot, away from the direct map and the image. */
    public static final long BASE = 0xffff_a000_0000_0000L;
    public static final long LIMIT = BASE + (512L << 30);
    /** The collector's side tables, in the next PML4 slot: mark bits, then the mark stack. */
    public static final long MARKS = 0xffff_b000_0000_0000L;
    public static final long MARK_STACK = MARKS + (16L << 30);

    private KernelHeap() {
    }

    public static void init() {
        PageTable table = KernelAddressSpace.table();
        long nx = KernelAddressSpace.noExecuteSupported() ? PageTable.NO_EXECUTE : 0;
        // Runs inside the allocator, so it must not allocate objects itself.
        Heap.growInto(BASE, LIMIT, MARKS, MARK_STACK, (address, bytes) -> {
            for (long page = address; page < address + bytes; page += PhysicalMemory.PAGE_SIZE) {
                long frame = PhysicalMemory.tryAllocateZeroed();
                if (frame < 0) {
                    return false;
                }
                table.map(page, frame, PageTable.WRITABLE | nx);
            }
            return true;
        });
    }
}
