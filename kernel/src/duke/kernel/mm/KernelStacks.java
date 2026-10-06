package duke.kernel.mm;

import duke.rt.Magic;

/**
 * Thread stacks, in their own PML4 slot. Each slot is a 64 KiB stack with an unmapped guard page
 * below it, so running off the end faults instead of overwriting the neighbour. Frames are mapped
 * the first time a slot is handed out and stay mapped after it comes back: a thread may have run
 * on any CPU, and unmapping would leave stale translations in their TLBs for the next thread on
 * that slot to write through.
 *
 * <p>Not reentrant: callers hold the scheduler lock, or run before the other CPUs start.
 */
public final class KernelStacks {

    public static final long BASE = 0xffff_c000_0000_0000L;
    public static final int STACK_BYTES = 64 * 1024;
    private static final long SLOT_BYTES = STACK_BYTES + PhysicalMemory.PAGE_SIZE;
    public static final int MAX_STACKS = 4096;

    private static final int[] FREE = new int[MAX_STACKS];
    private static int freeCount;
    private static int used;

    private KernelStacks() {
    }

    /** The lowest address of a fresh, zeroed stack, or -1 if every slot or frame is taken. */
    public static long allocate() {
        int slot;
        if (freeCount > 0) {
            long bottom = bottom(FREE[--freeCount]);
            Magic.zeroMemoryWords(bottom, STACK_BYTES / 8);
            return bottom;
        } else if (used < MAX_STACKS) {
            slot = used++;
        } else {
            return -1;
        }
        long bottom = bottom(slot);
        PageTable table = KernelAddressSpace.table();
        long nx = KernelAddressSpace.noExecuteSupported() ? PageTable.NO_EXECUTE : 0;
        for (long page = bottom; page < bottom + STACK_BYTES; page += PhysicalMemory.PAGE_SIZE) {
            long frame = PhysicalMemory.tryAllocateZeroed();
            if (frame < 0) {
                unmap(bottom, page);
                used--;
                return -1;
            }
            table.map(page, frame, PageTable.WRITABLE | nx);
        }
        return bottom;
    }

    /** Returns a stack from {@link #allocate}. Nothing may be running on it. */
    public static void free(long bottom) {
        FREE[freeCount++] = (int) ((bottom - PhysicalMemory.PAGE_SIZE - BASE) / SLOT_BYTES);
    }

    /** Stacks currently handed out. */
    public static int inUse() {
        return used - freeCount;
    }

    private static long bottom(int slot) {
        return BASE + slot * SLOT_BYTES + PhysicalMemory.PAGE_SIZE;
    }

    private static void unmap(long from, long to) {
        PageTable table = KernelAddressSpace.table();
        for (long page = from; page < to; page += PhysicalMemory.PAGE_SIZE) {
            long frame = table.translate(page);
            table.unmap(page);
            PhysicalMemory.free(frame);
        }
    }
}
