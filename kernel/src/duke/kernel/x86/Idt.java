package duke.kernel.x86;

import duke.rt.Magic;

/** The interrupt descriptor table: every vector points at its compiler-generated entry stub. */
public final class Idt {

    // Limine's 64-bit code segment.
    private static final int KERNEL_CODE_SELECTOR = 0x28;
    private static final long PRESENT_INTERRUPT_GATE = 0x8E;

    // 256 gates of 16 bytes. Heap objects, which is fine until a collector moves things (#17).
    private static final long[] TABLE = new long[512];
    private static final long[] DESCRIPTOR = new long[2];

    private Idt() {
    }

    public static void load() {
        long stubs = Magic.interruptStubs();
        for (int vector = 0; vector < 256; vector++) {
            long handler = Magic.peekLong(stubs + 8L * vector);
            TABLE[2 * vector] = (handler & 0xFFFF)
                    | (long) KERNEL_CODE_SELECTOR << 16
                    | PRESENT_INTERRUPT_GATE << 40
                    | (handler >>> 16 & 0xFFFF) << 48;
            TABLE[2 * vector + 1] = handler >>> 32;
        }
        long descriptor = Magic.addressOf(DESCRIPTOR) + 16;
        Magic.pokeShort(descriptor, (short) (256 * 16 - 1));
        Magic.pokeLong(descriptor + 2, Magic.addressOf(TABLE) + 16);
        Magic.loadIdt(descriptor);
    }
}
