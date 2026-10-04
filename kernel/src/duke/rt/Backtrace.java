package duke.rt;

import duke.kernel.Console;

/**
 * Walks the rbp chain and symbolizes return addresses through the compiler's method table.
 * Every compiled method starts with push rbp; mov rbp, rsp, and _start zeroes rbp, so the chain
 * ends at 0.
 */
public final class Backtrace {

    static final int ENTRY_SIZE = 56;
    static final int ENTRY_EXCEPTIONS = 40;
    static final int ENTRY_FLAGS = 48;
    static final int HIDDEN = 1;
    static final int INTERRUPT_ENTRY = 2;
    private static final int MAX_FRAMES = 64;

    private Backtrace() {
    }

    /**
     * Return addresses (minus one, ready to describe) of the caller's frames, skipping the leading
     * frames the compiler flags as hidden: runtime plumbing and Throwable constructors.
     */
    public static long[] capture() {
        long rbp = Magic.framePointer();
        long[] frames = new long[MAX_FRAMES];
        int count = 0;
        boolean leading = true;
        for (int depth = 0; rbp != 0 && depth < MAX_FRAMES; depth++) {
            long returnAddress = Magic.peekLong(rbp + 8);
            if (returnAddress == 0) {
                break;
            }
            long entry = find(returnAddress - 1);
            boolean hidden = entry != 0 && (Magic.peekInt(entry + ENTRY_FLAGS) & HIDDEN) != 0;
            if (!(leading && hidden)) {
                leading = false;
                frames[count++] = returnAddress - 1;
            }
            rbp = Magic.peekLong(rbp);
        }
        long[] trimmed = new long[count];
        System.arraycopy(frames, 0, trimmed, 0, count);
        return trimmed;
    }

    /** Prints the frames above the method that called this one. */
    public static void printCaller() {
        long rbp = Magic.peekLong(Magic.framePointer());
        print(Magic.peekLong(rbp + 8) - 1, Magic.peekLong(rbp));
    }

    /**
     * Prints {@code rip}, then every return address up the chain starting at {@code rbp}.
     * Return addresses are looked up minus one so a call at the very end of a method still
     * resolves to that method.
     */
    public static void print(long rip, long rbp) {
        printFrame(rip);
        for (int depth = 0; rbp != 0 && depth < MAX_FRAMES; depth++) {
            long returnAddress = Magic.peekLong(rbp + 8);
            if (returnAddress == 0) {
                break;
            }
            printFrame(returnAddress - 1);
            rbp = Magic.peekLong(rbp);
        }
    }

    private static void printFrame(long address) {
        Console.print("  at ");
        Console.println(describe(address));
    }

    /** "pkg.Class.method(File.java:42)", or a hex address if it isn't compiled code. */
    public static String describe(long address) {
        if (find(address) == 0) {
            return "0x" + Long.toHexString(address);
        }
        return element(address).toString();
    }

    public static StackTraceElement element(long address) {
        long entry = find(address);
        if (entry == 0) {
            return new StackTraceElement("", "0x" + Long.toHexString(address), null, -1);
        }
        String name = string(Magic.peekLong(entry + 16));
        long file = Magic.peekLong(entry + 24);
        int line = line(entry, (int) (address - Magic.peekLong(entry)));
        int dot = name.lastIndexOf('.');
        return new StackTraceElement(dot < 0 ? "" : name.substring(0, dot), name.substring(dot + 1),
                file == 0 ? null : string(file), line > 0 ? line : -1);
    }

    /** Binary search for the entry whose [start, start + size) holds the address, or 0. */
    static long find(long address) {
        long table = Magic.methodTable();
        long count = Magic.peekLong(table);
        long entries = table + 8;
        long low = 0;
        long high = count - 1;
        while (low <= high) {
            long mid = (low + high) >>> 1;
            long entry = entries + mid * ENTRY_SIZE;
            long start = Magic.peekLong(entry);
            if (address < start) {
                high = mid - 1;
            } else if (address >= start + Magic.peekInt(entry + 8)) {
                low = mid + 1;
            } else {
                return entry;
            }
        }
        return 0;
    }

    /**
     * The source line of the last line-table row at or before {@code offset}. Code before the first
     * row is the prologue (the stack check lives there), which belongs to the method's first line.
     */
    private static int line(long entry, int offset) {
        int count = Magic.peekInt(entry + 12);
        long table = Magic.peekLong(entry + 32);
        int line = count > 0 ? Magic.peekInt(table + 4) : 0;
        for (int i = 0; i < count && Magic.peekInt(table + 8L * i) <= offset; i++) {
            line = Magic.peekInt(table + 8L * i + 4);
        }
        return line;
    }

    private static String string(long address) {
        return (String) Magic.toObject(address);
    }
}
