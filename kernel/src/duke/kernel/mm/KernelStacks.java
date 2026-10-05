package duke.kernel.mm;

import duke.rt.Magic;

/**
 * Thread stacks, in their own PML4 slot. Each slot is a 64 KiB stack with an unmapped guard page
 * below it, so running off the end faults instead of overwriting the neighbour. Frames are mapped
 * when a stack is handed out and freed when it comes back.
 *
 * <p>Not reentrant: callers run with interrupts off.
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
            slot = FREE[--freeCount];
        } else if (used < MAX_STACKS) {
            slot = used++;
        } else {
            return -1;
        }
        long bottom = BASE + slot * SLOT_BYTES + PhysicalMemory.PAGE_SIZE;
        PageTable table = KernelAddressSpace.table();
        long nx = KernelAddressSpace.noExecuteSupported() ? PageTable.NO_EXECUTE : 0;
        for (long page = bottom; page < bottom + STACK_BYTES; page += PhysicalMemory.PAGE_SIZE) {
            long frame = PhysicalMemory.tryAllocate();
            if (frame < 0) {
                unmap(bottom, page);
                FREE[freeCount++] = slot;
                return -1;
            }
            Magic.fillMemory(PhysicalMemory.toVirtual(frame), 0, PhysicalMemory.PAGE_SIZE);
            table.map(page, frame, PageTable.WRITABLE | nx);
        }
        return bottom;
    }

    /** Returns a stack from {@link #allocate}. Nothing may be running on it. */
    public static void free(long bottom) {
        unmap(bottom, bottom + STACK_BYTES);
        FREE[freeCount++] = (int) ((bottom - PhysicalMemory.PAGE_SIZE - BASE) / SLOT_BYTES);
    }

    /** Stacks currently handed out. */
    public static int inUse() {
        return used - freeCount;
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
