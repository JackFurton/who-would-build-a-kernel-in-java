package duke.rt;

import duke.kernel.Panic;

/**
 * Bump allocator over a fixed arena the compiler reserves in .bss. Nothing is ever freed, so
 * memory needs no zeroing: the loader zeroes .bss and every byte is handed out once.
 */
public final class Heap {

    // Initialized lazily rather than in <clinit>: other initializers may allocate before ours runs.
    private static final int ARRAY_LENGTH_OFFSET = 8;
    private static final int ARRAY_DATA_OFFSET = 16;

    private static long next;
    private static long end;

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
            Panic.panic("NegativeArraySizeException", length, 0);
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
                Panic.panic("NegativeArraySizeException", count(counts, dimensions, i), i);
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

    private static long allocate(long size) {
        if (next == 0) {
            next = Magic.heapArenaStart();
            end = Magic.heapArenaEnd();
        }
        long address = next;
        long bumped = address + ((size + 7) & ~7L);
        // The arena is in the top 2 GiB, negative as a signed long. The largest possible request
        // (2^31 longs) crosses zero into positive values, which still compare greater than end.
        if (bumped > end) {
            Panic.panic("OutOfMemoryError: kernel heap arena exhausted");
        }
        next = bumped;
        return address;
    }

    public static long used() {
        return next == 0 ? 0 : next - Magic.heapArenaStart();
    }
}
