package duke.kernel.x86;

import duke.rt.Magic;

/** The interrupt descriptor table: every vector points at its compiler-generated entry stub. */
public final class Idt {

    private static final long PRESENT_INTERRUPT_GATE = 0x8E;
    private static final int DOUBLE_FAULT = 8;

    // 256 gates of 16 bytes. Heap objects, which is fine until a collector moves things (#17).
    private static final long[] TABLE = new long[512];
    private static final long[] DESCRIPTOR = new long[2];

    private Idt() {
    }

    public static void load() {
        Gdt.ensureLoaded();
        long stubs = Magic.interruptStubs();
        for (int vector = 0; vector < 256; vector++) {
            long handler = Magic.peekLong(stubs + 8L * vector);
            TABLE[2 * vector] = (handler & 0xFFFF)
                    | (long) Gdt.KERNEL_CODE << 16
                    | (vector == DOUBLE_FAULT ? (long) Gdt.DOUBLE_FAULT_IST << 32 : 0)
                    | PRESENT_INTERRUPT_GATE << 40
                    | (handler >>> 16 & 0xFFFF) << 48;
            TABLE[2 * vector + 1] = handler >>> 32;
        }
        long descriptor = Magic.addressOf(DESCRIPTOR) + 16;
        Magic.pokeShort(descriptor, (short) (256 * 16 - 1));
        Magic.pokeLong(descriptor + 2, Magic.addressOf(TABLE) + 16);
        Magic.loadIdt(descriptor);
    }

    /** Loads the table {@link #load} built onto another CPU: every CPU shares it. */
    public static void loadOnThisCpu() {
        Magic.loadIdt(Magic.addressOf(DESCRIPTOR) + 16);
    }
}
