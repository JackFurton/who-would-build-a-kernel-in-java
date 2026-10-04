package duke.kernel.mm;

import duke.rt.Heap;
import duke.rt.Magic;

/** Moves the Java heap off the early .bss arena into a region backed by frames on demand. */
public final class KernelHeap {

    /** 512 GiB of virtual space in its own PML4 slot, away from the direct map and the image. */
    public static final long BASE = 0xffff_a000_0000_0000L;
    public static final long LIMIT = BASE + (512L << 30);

    private KernelHeap() {
    }

    public static void init() {
        PageTable table = KernelAddressSpace.table();
        long nx = KernelAddressSpace.noExecuteSupported() ? PageTable.NO_EXECUTE : 0;
        // Runs inside the allocator, so it must not allocate objects itself.
        Heap.growInto(BASE, LIMIT, (address, bytes) -> {
            for (long page = address; page < address + bytes; page += PhysicalMemory.PAGE_SIZE) {
                long frame = PhysicalMemory.tryAllocate();
                if (frame < 0) {
                    return false;
                }
                Magic.fillMemory(PhysicalMemory.toVirtual(frame), 0, PhysicalMemory.PAGE_SIZE);
                table.map(page, frame, PageTable.WRITABLE | nx);
            }
            return true;
        });
    }
}
