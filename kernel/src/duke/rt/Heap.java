package duke.rt;

import duke.kernel.Panic;

/**
 * Bump allocator. Early boot allocates from a small arena the compiler reserves in .bss; once
 * paging is up, {@link #growInto} moves allocation to a large virtual region that is backed by
 * fresh zeroed frames on demand. Nothing is ever freed yet (#17), so memory needs no zeroing at
 * allocation: .bss is zeroed by the loader and committed memory by its Backing.
 */
public final class Heap {

    /** Makes [address, address + bytes) usable and zeroed, or returns false when memory has run out. */
    public interface Backing {
        boolean commit(long address, long bytes);
    }

    private static final int ARRAY_LENGTH_OFFSET = 8;
    private static final int ARRAY_DATA_OFFSET = 16;
    private static final long COMMIT_CHUNK = 2 * 1024 * 1024;

    // Set lazily rather than in <clinit>: other initializers may allocate before ours runs.
    private static long next;
    private static long end;
    private static long regionStart;
    private static long regionLimit;
    private static Backing backing;
    private static long earlyUsed;

    private Heap() {
    }

    /** Target of every {@code new}; the compiler passes the class's TIB and instance size. */
    static Object allocateObject(long tib, int size) {
        long address = allocate(size);
        Magic.pokeLong(address, tib);
        return Magic.toObject(address);
    }

    /** Target of {@code newarray} and {@code anewarray}. */
    static Object allocateArray(long tib, int length, int elementSize) {
        if (length < 0) {
            throw new NegativeArraySizeException(Integer.toString(length));
        }
        long address = allocate(ARRAY_DATA_OFFSET + (long) length * elementSize);
        Magic.pokeLong(address, tib);
        Magic.pokeInt(address + ARRAY_LENGTH_OFFSET, length);
        return Magic.toObject(address);
    }

    /**
     * Target of {@code multianewarray}. {@code descriptor} points at a compiler-emitted table:
     * int dimensions, int padding, then per level a TIB pointer and an element size (8 bytes each).
     * {@code counts} points at the dimension counts still on the caller's operand stack, first
     * dimension at the highest address.
     */
    static Object allocateMultiArray(long descriptor, long counts) {
        int dimensions = Magic.peekInt(descriptor);
        for (int i = 0; i < dimensions; i++) {
            if (count(counts, dimensions, i) < 0) {
                throw new NegativeArraySizeException(Integer.toString(count(counts, dimensions, i)));
            }
        }
        return allocateLevel(descriptor, counts, dimensions, 0);
    }

    private static Object allocateLevel(long descriptor, long counts, int dimensions, int level) {
        long entry = descriptor + 8 + 16L * level;
        int length = count(counts, dimensions, level);
        Object array = allocateArray(Magic.peekLong(entry), length, (int) Magic.peekLong(entry + 8));
        if (level + 1 < dimensions) {
            long elements = Magic.addressOf(array) + ARRAY_DATA_OFFSET;
            for (int i = 0; i < length; i++) {
                Object child = allocateLevel(descriptor, counts, dimensions, level + 1);
                Magic.pokeLong(elements + 8L * i, Magic.addressOf(child));
            }
        }
        return array;
    }

    private static int count(long counts, int dimensions, int level) {
        return Magic.peekInt(counts + 8L * (dimensions - 1 - level));
    }

    /**
     * Switches allocation to [base, limit), committed through {@code backing} as it fills. The
     * early arena's remainder is abandoned; objects already there stay valid.
     */
    public static void growInto(long base, long limit, Backing backing) {
        if (next == 0) {
            next = Magic.heapArenaStart();
        }
        earlyUsed = next - Magic.heapArenaStart();
        Heap.backing = backing;
        regionStart = base;
        regionLimit = limit;
        next = base;
        end = base;
    }

    private static long allocate(long size) {
        if (next == 0) {
            next = Magic.heapArenaStart();
            end = Magic.heapArenaEnd();
        }
        long address = next;
        long bumped = address + ((size + 7) & ~7L);
        // Both regions are in the upper half, negative as a signed long. The largest possible
        // request (2^31 longs) can't cross zero from there, so signed comparison is safe.
        if (bumped > end && !commitUpTo(bumped)) {
            // Fatal rather than thrown: there's no memory left to allocate the error object in.
            Panic.panic("OutOfMemoryError: Java heap space");
        }
        next = bumped;
        return address;
    }

    private static boolean commitUpTo(long needed) {
        if (backing == null || needed > regionLimit) {
            return false;
        }
        long newEnd = Math.min(regionLimit, (needed + COMMIT_CHUNK - 1) & -COMMIT_CHUNK);
        if (!backing.commit(end, newEnd - end)) {
            return false;
        }
        end = newEnd;
        return true;
    }

    /** Bytes committed to the heap so far, including the early arena's used part. */
    public static long committed() {
        return backing == null ? used() : earlyUsed + (end - regionStart);
    }

    public static boolean inGrowableRegion(Object object) {
        long address = Magic.addressOf(object);
        return backing != null && address >= regionStart && address < regionLimit;
    }

    /** Address-derived. A moving collector (#17) will have to store it in the header first. */
    public static int identityHash(Object object) {
        long address = Magic.addressOf(object);
        return (int) (address >>> 3) ^ (int) (address >>> 35);
    }

    /** Bytes handed out so far. */
    public static long used() {
        if (backing != null) {
            return earlyUsed + (next - regionStart);
        }
        return next == 0 ? 0 : next - Magic.heapArenaStart();
    }
}
