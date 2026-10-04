package duke.kernel.mm;

import duke.boot.Limine;
import duke.rt.Magic;

/**
 * Physical page frames from the Limine memory map. Frames are named by physical address; the
 * kernel touches them through the higher-half direct map. Only "usable" regions are handed out.
 * Bootloader-reclaimable memory still holds Limine's page tables and responses until we have our
 * own (#15), so it stays reserved for now.
 */
public final class PhysicalMemory {

    public static final long PAGE_SIZE = 4096;

    private static FrameBitmap frames;
    private static long hhdm;

    private PhysicalMemory() {
    }

    public static void init() {
        hhdm = Limine.hhdmOffset();
        long top = 0;
        for (int i = 0; i < Limine.memoryMapSize(); i++) {
            if (Limine.memoryMapType(i) == Limine.MEMMAP_USABLE) {
                top = Math.max(top, Limine.memoryMapBase(i) + Limine.memoryMapLength(i));
            }
        }
        frames = new FrameBitmap(top / PAGE_SIZE);
        for (int i = 0; i < Limine.memoryMapSize(); i++) {
            if (Limine.memoryMapType(i) == Limine.MEMMAP_USABLE) {
                // Usable regions are page-aligned per the protocol; round inward anyway.
                // Never hand out frame 0: 0 reads as "no frame" everywhere, and it's the real-mode IVT.
                long first = Math.max(1, (Limine.memoryMapBase(i) + PAGE_SIZE - 1) / PAGE_SIZE);
                long end = (Limine.memoryMapBase(i) + Limine.memoryMapLength(i)) / PAGE_SIZE;
                if (end > first) {
                    frames.release(first, end - first);
                }
            }
        }
    }

    /** A free frame's physical address. Contents are whatever was there before. */
    public static long allocate() {
        long frame = tryAllocate();
        if (frame < 0) {
            throw new OutOfMemoryError("out of physical memory");
        }
        return frame;
    }

    /** Like allocate(), but -1 instead of throwing: for paths that can't allocate an exception. */
    public static long tryAllocate() {
        long page = frames.allocate();
        return page < 0 ? -1 : page * PAGE_SIZE;
    }

    public static long allocateZeroed() {
        long frame = allocate();
        Magic.fillMemory(toVirtual(frame), 0, PAGE_SIZE);
        return frame;
    }

    public static void free(long physical) {
        if (physical % PAGE_SIZE != 0) {
            throw new IllegalArgumentException("not a frame address: 0x" + Long.toHexString(physical));
        }
        frames.free(physical / PAGE_SIZE);
    }

    public static long toVirtual(long physical) {
        return physical + hhdm;
    }

    public static long freeFrames() {
        return frames.freePages();
    }
}
