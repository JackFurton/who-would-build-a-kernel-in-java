package duke.kernel.x86;

import duke.rt.Magic;

/**
 * Our own GDT, replacing Limine's (which lives in bootloader-reclaimable memory): null, 64-bit
 * kernel code and data, and a TSS. The TSS exists for IST 1, the double-fault stack, so a fault
 * that can't push its frame on the current stack still has somewhere to land.
 */
public final class Gdt {

    public static final int KERNEL_CODE = 0x08;
    public static final int KERNEL_DATA = 0x10;
    public static final int TSS = 0x18;
    public static final int DOUBLE_FAULT_IST = 1;

    // Present, ring 0, code (execute/read) with the long-mode bit; and present, ring 0, read/write data.
    private static final long CODE_DESCRIPTOR = 0x00209A0000000000L;
    private static final long DATA_DESCRIPTOR = 0x0000920000000000L;
    private static final long[] TABLE = {0, CODE_DESCRIPTOR, DATA_DESCRIPTOR, 0, 0};
    private static final long[] DESCRIPTOR = new long[2];
    // 104-byte 64-bit TSS. Image data, so it never moves.
    private static final long[] TSS_DATA = new long[13];
    private static final long[] DOUBLE_FAULT_STACK = new long[2048];
    private static final int TSS_LIMIT = 103;
    private static final int TSS_IST1 = 36;
    private static final int TSS_IO_MAP_BASE = 102;
    private static final int MAX_CPUS = 64;
    /** Other CPUs' GDTs and TSSs, kept reachable: each CPU reads its own on every interrupt. */
    private static final long[][] OTHER_CPUS = new long[4 * MAX_CPUS][];

    private static boolean loaded;

    private Gdt() {
    }

    /** The boot CPU's GDT and TSS, which live in the image. */
    public static void ensureLoaded() {
        if (!loaded) {
            install(build(TABLE, DESCRIPTOR, TSS_DATA, DOUBLE_FAULT_STACK));
            loaded = true;
        }
    }

    /**
     * A GDT, TSS and double-fault stack for another CPU, built by the boot CPU so the other one
     * needn't allocate. Returns the descriptor to {@link #install} there.
     */
    public static long prepare(int cpu) {
        long[] table = {0, CODE_DESCRIPTOR, DATA_DESCRIPTOR, 0, 0};
        long[] descriptor = new long[2];
        long[] tss = new long[13];
        long[] stack = new long[2048];
        OTHER_CPUS[4 * cpu] = table;
        OTHER_CPUS[4 * cpu + 1] = descriptor;
        OTHER_CPUS[4 * cpu + 2] = tss;
        OTHER_CPUS[4 * cpu + 3] = stack;
        return build(table, descriptor, tss, stack);
    }

    /** Loads a GDT from {@link #prepare} on the calling CPU, with its segments and TSS. */
    public static void install(long descriptor) {
        Magic.loadGdt(descriptor);
        Magic.loadSegments(KERNEL_CODE, KERNEL_DATA);
        Magic.loadTaskRegister(TSS);
    }

    private static long build(long[] table, long[] descriptorWords, long[] tssData, long[] doubleFaultStack) {
        long tss = Magic.addressOf(tssData) + 16;
        Magic.pokeLong(tss + TSS_IST1, Magic.addressOf(doubleFaultStack) + 16 + 8L * doubleFaultStack.length);
        Magic.pokeShort(tss + TSS_IO_MAP_BASE, (short) (TSS_LIMIT + 1));
        // An available 64-bit TSS (type 0x89); the base is split across both descriptor words.
        table[3] = TSS_LIMIT | (tss & 0xFF_FFFF) << 16 | 0x89L << 40 | (tss >>> 24 & 0xFF) << 56;
        table[4] = tss >>> 32;

        long descriptor = Magic.addressOf(descriptorWords) + 16;
        Magic.pokeShort(descriptor, (short) (8 * table.length - 1));
        Magic.pokeLong(descriptor + 2, Magic.addressOf(table) + 16);
        return descriptor;
    }
}
