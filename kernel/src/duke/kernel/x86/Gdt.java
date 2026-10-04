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
    private static final long[] TABLE = {0, 0x00209A0000000000L, 0x0000920000000000L, 0, 0};
    private static final long[] DESCRIPTOR = new long[2];
    // 104-byte 64-bit TSS. Image data, so it never moves.
    private static final long[] TSS_DATA = new long[13];
    private static final long[] DOUBLE_FAULT_STACK = new long[2048];
    private static final int TSS_LIMIT = 103;
    private static final int TSS_IST1 = 36;
    private static final int TSS_IO_MAP_BASE = 102;

    private static boolean loaded;

    private Gdt() {
    }

    public static void ensureLoaded() {
        if (!loaded) {
            load();
        }
    }

    private static void load() {
        long tss = Magic.addressOf(TSS_DATA) + 16;
        Magic.pokeLong(tss + TSS_IST1, Magic.addressOf(DOUBLE_FAULT_STACK) + 16 + 8L * DOUBLE_FAULT_STACK.length);
        Magic.pokeShort(tss + TSS_IO_MAP_BASE, (short) (TSS_LIMIT + 1));
        // An available 64-bit TSS (type 0x89); the base is split across both descriptor words.
        TABLE[3] = TSS_LIMIT | (tss & 0xFF_FFFF) << 16 | 0x89L << 40 | (tss >>> 24 & 0xFF) << 56;
        TABLE[4] = tss >>> 32;

        long descriptor = Magic.addressOf(DESCRIPTOR) + 16;
        Magic.pokeShort(descriptor, (short) (8 * TABLE.length - 1));
        Magic.pokeLong(descriptor + 2, Magic.addressOf(TABLE) + 16);
        Magic.loadGdt(descriptor);
        Magic.loadSegments(KERNEL_CODE, KERNEL_DATA);
        Magic.loadTaskRegister(TSS);
        loaded = true;
    }
}
