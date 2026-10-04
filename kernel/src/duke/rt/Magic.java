package duke.rt;

/**
 * Compiler intrinsics. Every method here is replaced by inline machine code; none has a body, and
 * calling one through anything but a direct static call is a compile error.
 */
public final class Magic {

    private Magic() {
    }

    public static native void outb(int port, int value);

    public static native void outw(int port, int value);

    public static native void outl(int port, int value);

    public static native int inb(int port);

    public static native int inw(int port);

    public static native int inl(int port);

    public static native byte peekByte(long address);

    public static native short peekShort(long address);

    public static native int peekInt(long address);

    public static native long peekLong(long address);

    public static native void pokeByte(long address, byte value);

    public static native void pokeShort(long address, short value);

    public static native void pokeInt(long address, int value);

    public static native void pokeLong(long address, long value);

    /** memmove: correct for overlapping ranges in either direction. */
    public static native void copyMemory(long destination, long source, long bytes);

    /** memset: {@code bytes} copies of the low byte of {@code value} at {@code destination}. */
    public static native void fillMemory(long destination, int value, long bytes);

    public static native long addressOf(Object object);

    public static native Object toObject(long address);

    public static native long heapArenaStart();

    public static native long heapArenaEnd();

    /**
     * Address of 256 code pointers, one entry stub per interrupt vector. Each stub saves every
     * general-purpose register and calls {@code duke.kernel.x86.Interrupts.dispatch(frame)}, where
     * {@code frame} points at the saved registers (layout in duke.kernel.x86.Frame).
     */
    public static native long interruptStubs();

    public static native void loadIdt(long descriptor);

    public static native void loadGdt(long descriptor);

    public static native long readCr0();

    public static native long readCr2();

    public static native long readCr3();

    public static native long readCr4();

    public static native void writeCr0(long value);

    public static native void writeCr3(long value);

    public static native void writeCr4(long value);

    public static native void invalidatePage(long address);

    public static native long readMsr(int msr);

    public static native void writeMsr(int msr, long value);

    public static native long readTimestamp();

    /** RFLAGS, for saving and restoring the interrupt flag. */
    public static native long flags();

    /** Writes eax, ebx, ecx, edx as four ints at {@code out}. */
    public static native void cpuid(int leaf, int subleaf, long out);

    /** The caller's rbp. Every compiled method keeps a frame pointer, so this starts a stack walk. */
    public static native long framePointer();

    /** The compiler's method table; layout documented on Compiler.emitMethodTable. */
    public static native long methodTable();

    /**
     * Abandons the current frames: sets rsp and rbp, pushes the exception and jumps to the handler.
     * Only duke.rt.Exceptions should call this.
     */
    public static native void resumeAt(long handler, long rsp, long rbp, Throwable exception);

    /** Back to the normal stack limit, after unwinding out of a StackOverflowError's reserve. */
    public static native void resetStackLimit();

    /** int3. */
    public static native void breakpoint();

    public static native void halt();

    public static native void disableInterrupts();

    public static native void enableInterrupts();

    public static native void pause();
}
