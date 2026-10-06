package duke.rt;

import duke.kernel.Panic;
import duke.kernel.Scheduler;

/**
 * Non-moving mark-sweep. Roots are static reference fields, build-time reference arrays, and every
 * frame on every thread's stack, read through the compiler's per-call-site stack maps. Marking uses a bit per
 * 8 heap bytes and an explicit mark stack. Sweeping walks both heap regions object by object,
 * coalesces dead runs into holes and rebuilds the free list.
 *
 * <p>Runs inside an allocation, so it must not allocate: everything here is primitives and Magic.
 */
final class Collector {

    private static final long MARK_STACK_CHUNK = 64 * 1024;

    /** Where the explicit mark stack lives; committed through the heap's Backing as it grows. */
    static long markStack;
    private static long markStackCommitted;
    private static long markStackTop;

    static long collections;
    static long lastLive;
    static long lastFreed;

    private Collector() {
    }

    /** Returns live bytes. */
    static long collect() {
        clearMarks();
        markStackTop = markStack;
        markStaticRoots();
        markImageRoots();
        markStack(Magic.framePointer());
        for (int i = 0; i < Scheduler.taskCount(); i++) {
            long frame = Scheduler.parkedFrame(i);
            if (frame != 0) {
                markStack(frame);
            }
        }
        drain();
        long live = sweep();
        collections++;
        lastLive = live;
        return live;
    }

    private static void clearMarks() {
        for (int i = 0; i < Heap.ARENA_MARKS.length; i++) {
            Heap.ARENA_MARKS[i] = 0;
        }
        Magic.fillMemory(Heap.regionMarks, 0, (Heap.end - Heap.regionStart) / 64);
    }

    private static void markStaticRoots() {
        long table = Magic.gcStaticRoots();
        long count = Magic.peekLong(table);
        for (long i = 0; i < count; i++) {
            mark(Magic.peekLong(Magic.peekLong(table + 8 + 8 * i)));
        }
    }

    private static void markImageRoots() {
        long table = Magic.gcImageRoots();
        long count = Magic.peekLong(table);
        for (long i = 0; i < count; i++) {
            scanArray(Magic.peekLong(table + 8 + 8 * i));
        }
    }

    /**
     * Walks the rbp chain from the collector's own frame. Each frame's return address is a call
     * site in its caller, and that call site's stack map lists the caller's reference slots
     * relative to the caller's rbp. Frames with no map table are stubs without Java slots.
     */
    private static void markStack(long rbp) {
        while (rbp != 0) {
            long returnAddress = Magic.peekLong(rbp + 8);
            long callerRbp = Magic.peekLong(rbp);
            if (returnAddress == 0) {
                break;
            }
            long entry = Backtrace.find(returnAddress - 1);
            if (entry != 0) {
                long map = Magic.peekLong(entry + Backtrace.ENTRY_GC_MAP);
                if (map != 0) {
                    markFrame(map, (int) (returnAddress - Magic.peekLong(entry)), callerRbp, returnAddress);
                }
            }
            rbp = callerRbp;
        }
    }

    private static void markFrame(long map, int offset, long rbp, long returnAddress) {
        int sites = Magic.peekInt(map);
        long site = map + 4;
        for (int i = 0; i < sites; i++) {
            int slots = Magic.peekInt(site + 4);
            if (Magic.peekInt(site) == offset) {
                for (int s = 0; s < slots; s++) {
                    mark(Magic.peekLong(rbp + Magic.peekInt(site + 8 + 4L * s)));
                }
                return;
            }
            site += 8 + 4L * slots;
        }
        Panic.panic("GC: no stack map for call site ", Backtrace.describe(returnAddress - 1));
    }

    static boolean inHeap(long address) {
        if ((address & 7) != 0) {
            return false;
        }
        if (address >= Heap.arenaStart && address < Heap.arenaEnd) {
            return true;
        }
        return address >= Heap.regionStart && address < Heap.next;
    }

    /** Address of the mark word for a heap address, and the bit within it. */
    private static long markWord(long address) {
        if (address < Heap.arenaEnd && address >= Heap.arenaStart) {
            return Magic.addressOf(Heap.ARENA_MARKS) + 16 + ((address - Heap.arenaStart) >>> 9) * 8;
        }
        return Heap.regionMarks + ((address - Heap.regionStart) >>> 9) * 8;
    }

    private static long markBit(long address, long base) {
        return 1L << ((address - base) >>> 3);
    }

    static boolean isMarked(long address) {
        long base = address >= Heap.arenaStart && address < Heap.arenaEnd ? Heap.arenaStart : Heap.regionStart;
        return (Magic.peekLong(markWord(address)) & markBit(address, base)) != 0;
    }

    private static void mark(long address) {
        if (address == 0 || !inHeap(address)) {
            return;
        }
        long base = address < Heap.arenaEnd && address >= Heap.arenaStart ? Heap.arenaStart : Heap.regionStart;
        long word = markWord(address);
        long bit = markBit(address, base);
        long bits = Magic.peekLong(word);
        if ((bits & bit) != 0) {
            return;
        }
        Magic.pokeLong(word, bits | bit);
        push(address);
    }

    private static void push(long address) {
        if (markStackTop + 8 > markStack + markStackCommitted) {
            if (!Heap.backing.commit(markStack + markStackCommitted, MARK_STACK_CHUNK)) {
                Panic.panic("GC: out of memory for the mark stack");
            }
            markStackCommitted += MARK_STACK_CHUNK;
        }
        Magic.pokeLong(markStackTop, address);
        markStackTop += 8;
    }

    private static void drain() {
        while (markStackTop > markStack) {
            markStackTop -= 8;
            long object = Magic.peekLong(markStackTop);
            long tib = Magic.peekLong(object);
            if (tib == Heap.FILLER_8 || tib == Heap.FILLER_16 || tib == Heap.HOLE) {
                Panic.panic("GC: reference to freed memory at 0x", Long.toHexString(object));
            }
            int flags = Magic.peekInt(tib + Tib.FLAGS);
            if ((flags & Tib.FLAG_REFERENCE_ARRAY) != 0) {
                scanArray(object);
            } else if ((flags & Tib.FLAG_ARRAY) == 0) {
                long references = Magic.peekLong(tib + Tib.REFERENCE_FIELDS);
                if (references != 0) {
                    int count = Magic.peekInt(references);
                    for (int i = 0; i < count; i++) {
                        mark(Magic.peekLong(object + Magic.peekInt(references + 4 + 4L * i)));
                    }
                }
            }
        }
    }

    private static void scanArray(long array) {
        int length = Magic.peekInt(array + Tib.ARRAY_LENGTH);
        long elements = array + Tib.ARRAY_DATA;
        for (int i = 0; i < length; i++) {
            mark(Magic.peekLong(elements + 8L * i));
        }
    }

    /** Size of the object or hole at {@code address}. */
    static long sizeAt(long address) {
        long header = Magic.peekLong(address);
        if (header == Heap.FILLER_8) {
            return 8;
        }
        if (header == Heap.FILLER_16) {
            return 16;
        }
        if (header == Heap.HOLE) {
            return Magic.peekLong(address + 8);
        }
        int flags = Magic.peekInt(header + Tib.FLAGS);
        int size = Magic.peekInt(header + Tib.SIZE);
        if ((flags & Tib.FLAG_ARRAY) != 0) {
            return (Tib.ARRAY_DATA + (long) Magic.peekInt(address + Tib.ARRAY_LENGTH) * size + 7) & ~7L;
        }
        return size;
    }

    /**
     * Rebuilds the free list in address order: arena first, then the growable region. A dead run
     * that reaches the bump pointer is handed back to the bump allocator instead.
     */
    private static long sweep() {
        Heap.freeList = 0;
        lastTail = 0;
        long live = sweepRange(Heap.arenaStart, Heap.arenaEnd, false);
        live += sweepRange(Heap.regionStart, Heap.next, true);
        return live;
    }

    private static long lastTail;

    private static long sweepRange(long from, long to, boolean bumpTail) {
        long live = 0;
        long run = 0;
        long address = from;
        while (address < to) {
            long size = sizeAt(address);
            long header = Magic.peekLong(address);
            boolean dead = header == Heap.FILLER_8 || header == Heap.FILLER_16 || header == Heap.HOLE || !isMarked(address);
            if (dead) {
                if (run == 0) {
                    run = address;
                }
            } else {
                live += size;
                if (run != 0) {
                    addHole(run, address - run);
                    run = 0;
                }
            }
            address += size;
        }
        if (run != 0) {
            if (bumpTail) {
                lastFreed += Heap.next - run;
                Heap.next = run;
                // The bump allocator hands out zeroed memory; the dead objects here weren't.
                Magic.zeroMemoryWords(run, (to - run) >>> 3);
            } else {
                addHole(run, to - run);
            }
        }
        return live;
    }

    private static void addHole(long address, long size) {
        lastFreed += size;
        if (size == 8) {
            Magic.pokeLong(address, Heap.FILLER_8);
            return;
        }
        if (size == 16) {
            Magic.pokeLong(address, Heap.FILLER_16);
            return;
        }
        Magic.pokeLong(address, Heap.HOLE);
        Magic.pokeLong(address + 8, size);
        Magic.pokeLong(address + 16, 0);
        if (lastTail == 0) {
            Heap.freeList = address;
        } else {
            Magic.pokeLong(lastTail + 16, address);
        }
        lastTail = address;
    }
}
