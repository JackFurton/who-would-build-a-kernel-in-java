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

    public static native long addressOf(Object object);

    public static native void halt();

    public static native void disableInterrupts();

    public static native void enableInterrupts();

    public static native void pause();
}
