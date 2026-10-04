package duke.boot;

import duke.rt.Magic;

/**
 * Limine boot protocol requests (github.com/Limine-Bootloader/limine-protocol, PROTOCOL.md).
 * Each request is a static final long[] the compiler builds at build time, so it sits in the
 * image where Limine scans for it, 8-byte aligned. Limine writes the response pointer into the
 * array's last slot before the kernel starts. Every image includes this class: without the base
 * revision tag Limine would fall back to legacy revision 0 mappings.
 */
public final class Limine {

    private static final long MAGIC_0 = 0xc7b1dd30df4c8b88L;
    private static final long MAGIC_1 = 0x0a82e883a194f07bL;
    private static final int RESPONSE = 5;

    /** Limine zeroes [2] if it supports the revision and stores the revision it loaded with in [1]. */
    static final long[] BASE_REVISION = {0xf9562b2d5c95a6c8L, 0x6a7b384944536bdcL, 6};

    static final long[] HHDM = {MAGIC_0, MAGIC_1, 0x48dcf1cb8ad2b852L, 0x63984e959a98244bL, 0, 0};
    static final long[] MEMMAP = {MAGIC_0, MAGIC_1, 0x67cf3d9d378a806fL, 0xe304acdfc50c3c62L, 0, 0};
    static final long[] EXECUTABLE_ADDRESS = {MAGIC_0, MAGIC_1, 0x71ba76863cc55f63L, 0xb2644a48c516a487L, 0, 0};
    static final long[] FRAMEBUFFER = {MAGIC_0, MAGIC_1, 0x9d5827dcd881dd75L, 0xa3148604f6fab11bL, 0, 0};
    static final long[] RSDP = {MAGIC_0, MAGIC_1, 0xc5e77b6b397e7b43L, 0x27637845accdcf3cL, 0, 0};

    public static final int MEMMAP_USABLE = 0;
    public static final int MEMMAP_RESERVED = 1;
    public static final int MEMMAP_ACPI_RECLAIMABLE = 2;
    public static final int MEMMAP_ACPI_NVS = 3;
    public static final int MEMMAP_BAD_MEMORY = 4;
    public static final int MEMMAP_BOOTLOADER_RECLAIMABLE = 5;
    public static final int MEMMAP_EXECUTABLE_AND_MODULES = 6;
    public static final int MEMMAP_FRAMEBUFFER = 7;
    public static final int MEMMAP_RESERVED_MAPPED = 8;

    private static final String[] MEMMAP_TYPES = {
        "usable", "reserved", "ACPI reclaimable", "ACPI NVS", "bad memory", "bootloader reclaimable",
        "kernel and modules", "framebuffer", "reserved (mapped)"};

    private Limine() {
    }

    // Copies of the responses: Limine keeps them in bootloader-reclaimable memory, which the
    // kernel frees once it runs on its own page tables and GDT (PhysicalMemory.reclaimBootloaderMemory).
    private static boolean snapshotted;
    private static long hhdmOffset;
    private static long[] memmapBase;
    private static long[] memmapLength;
    private static int[] memmapType;
    private static long kernelPhysicalBase;
    private static long kernelVirtualBase;
    private static long framebufferAddress;
    private static long framebufferWidth;
    private static long framebufferHeight;
    private static long framebufferPitch;
    private static int framebufferBitsPerPixel;
    private static long rsdp;

    /** Copies every response into the kernel's own memory. Must run before reclaiming. */
    public static void snapshot() {
        if (snapshotted) {
            return;
        }
        hhdmOffset = Magic.peekLong(response(HHDM) + 8);
        int count = (int) Magic.peekLong(response(MEMMAP) + 8);
        memmapBase = new long[count];
        memmapLength = new long[count];
        memmapType = new int[count];
        long entries = Magic.peekLong(response(MEMMAP) + 16);
        for (int i = 0; i < count; i++) {
            long entry = Magic.peekLong(entries + 8L * i);
            memmapBase[i] = Magic.peekLong(entry);
            memmapLength[i] = Magic.peekLong(entry + 8);
            memmapType[i] = (int) Magic.peekLong(entry + 16);
        }
        kernelPhysicalBase = Magic.peekLong(response(EXECUTABLE_ADDRESS) + 8);
        kernelVirtualBase = Magic.peekLong(response(EXECUTABLE_ADDRESS) + 16);
        long fb = firstFramebuffer();
        if (fb != 0) {
            framebufferAddress = Magic.peekLong(fb);
            framebufferWidth = Magic.peekLong(fb + 8);
            framebufferHeight = Magic.peekLong(fb + 16);
            framebufferPitch = Magic.peekLong(fb + 24);
            framebufferBitsPerPixel = Magic.peekShort(fb + 32) & 0xFFFF;
        }
        long rsdpResponse = RSDP[RESPONSE];
        rsdp = rsdpResponse == 0 ? 0 : Magic.peekLong(rsdpResponse + 8);
        snapshotted = true;
    }

    public static boolean baseRevisionSupported() {
        return BASE_REVISION[2] == 0;
    }

    /** The revision Limine actually loaded us with. */
    public static long loadedBaseRevision() {
        return BASE_REVISION[1];
    }

    /** Virtual address of physical address 0 in the higher-half direct map. */
    public static long hhdmOffset() {
        snapshot();
        return hhdmOffset;
    }

    public static int memoryMapSize() {
        snapshot();
        return memmapBase.length;
    }

    public static long memoryMapBase(int index) {
        snapshot();
        return memmapBase[index];
    }

    public static long memoryMapLength(int index) {
        snapshot();
        return memmapLength[index];
    }

    public static int memoryMapType(int index) {
        snapshot();
        return memmapType[index];
    }

    public static String memoryMapTypeName(int type) {
        return type >= 0 && type < MEMMAP_TYPES.length ? MEMMAP_TYPES[type] : "type " + type;
    }

    public static long kernelPhysicalBase() {
        snapshot();
        return kernelPhysicalBase;
    }

    public static long kernelVirtualBase() {
        snapshot();
        return kernelVirtualBase;
    }

    /** The first framebuffer's address, or 0 without one (QEMU's -display none still has one). */
    public static long framebufferAddress() {
        snapshot();
        return framebufferAddress;
    }

    public static long framebufferWidth() {
        snapshot();
        return framebufferWidth;
    }

    public static long framebufferHeight() {
        snapshot();
        return framebufferHeight;
    }

    public static long framebufferPitch() {
        snapshot();
        return framebufferPitch;
    }

    public static int framebufferBitsPerPixel() {
        snapshot();
        return framebufferBitsPerPixel;
    }

    /** RSDP address as Limine reports it, or 0 without ACPI. */
    public static long rsdp() {
        snapshot();
        return rsdp;
    }

    private static long firstFramebuffer() {
        long response = FRAMEBUFFER[RESPONSE];
        if (response == 0 || Magic.peekLong(response + 8) == 0) {
            return 0;
        }
        return Magic.peekLong(Magic.peekLong(response + 16));
    }

    private static long response(long[] request) {
        long response = request[RESPONSE];
        if (response == 0) {
            throw new IllegalStateException("Limine did not answer request 0x" + Long.toHexString(request[2]));
        }
        return response;
    }
}
