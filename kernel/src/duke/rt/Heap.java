package duke.rt;

import duke.kernel.Panic;

/**
 * Bump allocator over a fixed arena the compiler reserves in .bss. Nothing is ever freed, so
 * memory needs no zeroing: the loader zeroes .bss and every byte is handed out once.
 */
public final class Heap {

    // Initialized lazily rather than in <clinit>: other initializers may allocate before ours runs.
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

    private static long allocate(int size) {
        if (next == 0) {
            next = Magic.heapArenaStart();
            end = Magic.heapArenaEnd();
        }
        long address = next;
        long bumped = address + ((size + 7) & ~7L);
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
