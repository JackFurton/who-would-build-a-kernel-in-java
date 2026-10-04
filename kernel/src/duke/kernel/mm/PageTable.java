package duke.kernel.mm;

import duke.rt.Magic;

/**
 * An x86-64 4-level page table (PML4 -> PDPT -> PD -> PT). Tables are physical frames, read and
 * written through the direct map. Intermediate entries are present and writable with no NX, so
 * the leaf entry alone decides a page's permissions.
 */
public final class PageTable {

    public static final long PRESENT = 1;
    public static final long WRITABLE = 1 << 1;
    public static final long USER = 1 << 2;
    public static final long WRITE_THROUGH = 1 << 3;
    public static final long CACHE_DISABLE = 1 << 4;
    public static final long HUGE = 1 << 7;
    public static final long GLOBAL = 1 << 8;
    public static final long NO_EXECUTE = 1L << 63;

    public static final long HUGE_PAGE_SIZE = 2 * 1024 * 1024;
    private static final long ADDRESS_MASK = 0x000F_FFFF_FFFF_F000L;

    private final long root;
    private int tables = 1;

    public PageTable() {
        root = PhysicalMemory.allocateZeroed();
    }

    /** Physical address of the PML4, for CR3. */
    public long root() {
        return root;
    }

    /** Page-table frames this table owns, including the root. */
    public int tableFrames() {
        return tables;
    }

    public void map(long virtual, long physical, long flags) {
        checkAligned(virtual, physical, PhysicalMemory.PAGE_SIZE);
        long entry = entry(virtual, 1, true);
        Magic.pokeLong(entry, physical | flags | PRESENT);
    }

    public void mapHuge(long virtual, long physical, long flags) {
        checkAligned(virtual, physical, HUGE_PAGE_SIZE);
        long entry = entry(virtual, 2, true);
        Magic.pokeLong(entry, physical | flags | PRESENT | HUGE);
    }

    /** Maps [virtual, virtual + length) to the same-sized physical range, with 2 MiB pages where both line up. */
    public void mapRange(long virtual, long physical, long length, long flags) {
        long offset = 0;
        while (offset < length) {
            long v = virtual + offset;
            long p = physical + offset;
            if (v % HUGE_PAGE_SIZE == 0 && p % HUGE_PAGE_SIZE == 0 && length - offset >= HUGE_PAGE_SIZE) {
                mapHuge(v, p, flags);
                offset += HUGE_PAGE_SIZE;
            } else {
                map(v, p, flags);
                offset += PhysicalMemory.PAGE_SIZE;
            }
        }
    }

    /** Removes a 4 KiB mapping and flushes it from this CPU's TLB. */
    public void unmap(long virtual) {
        long entry = entry(virtual, 1, false);
        if (entry != 0) {
            Magic.pokeLong(entry, 0);
            Magic.invalidatePage(virtual);
        }
    }

    /** The physical address {@code virtual} maps to, or -1 if it isn't mapped. */
    public long translate(long virtual) {
        long table = root;
        for (int level = 4; level >= 1; level--) {
            long value = Magic.peekLong(PhysicalMemory.toVirtual(table) + 8 * index(virtual, level));
            if ((value & PRESENT) == 0) {
                return -1;
            }
            if (level == 1 || (level == 2 && (value & HUGE) != 0)) {
                long pageSize = level == 1 ? PhysicalMemory.PAGE_SIZE : HUGE_PAGE_SIZE;
                return (value & ADDRESS_MASK & -pageSize) + (virtual & (pageSize - 1));
            }
            table = value & ADDRESS_MASK;
        }
        return -1;
    }

    /** The leaf entry's flags for {@code virtual}, or 0 if it isn't mapped. */
    public long flags(long virtual) {
        long table = root;
        for (int level = 4; level >= 1; level--) {
            long value = Magic.peekLong(PhysicalMemory.toVirtual(table) + 8 * index(virtual, level));
            if ((value & PRESENT) == 0) {
                return 0;
            }
            if (level == 1 || (level == 2 && (value & HUGE) != 0)) {
                return value & ~ADDRESS_MASK;
            }
            table = value & ADDRESS_MASK;
        }
        return 0;
    }

    /**
     * Virtual address of the entry for {@code virtual} at {@code level} (1 = PT, 2 = PD), creating
     * missing intermediate tables when {@code create}, else returning 0 where one is missing.
     */
    private long entry(long virtual, int level, boolean create) {
        long table = root;
        for (int l = 4; l > level; l--) {
            long slot = PhysicalMemory.toVirtual(table) + 8 * index(virtual, l);
            long value = Magic.peekLong(slot);
            if ((value & PRESENT) == 0) {
                if (!create) {
                    return 0;
                }
                value = PhysicalMemory.allocateZeroed() | PRESENT | WRITABLE;
                Magic.pokeLong(slot, value);
                tables++;
            } else if ((value & HUGE) != 0) {
                throw new IllegalStateException("0x" + Long.toHexString(virtual) + " is inside a huge page");
            }
            table = value & ADDRESS_MASK;
        }
        return PhysicalMemory.toVirtual(table) + 8 * index(virtual, level);
    }

    private static long index(long virtual, int level) {
        return (virtual >>> (12 + 9 * (level - 1))) & 511;
    }

    private static void checkAligned(long virtual, long physical, long size) {
        if (virtual % size != 0 || physical % size != 0) {
            throw new IllegalArgumentException("unaligned mapping 0x" + Long.toHexString(virtual) + " -> 0x"
                    + Long.toHexString(physical));
        }
    }
}
