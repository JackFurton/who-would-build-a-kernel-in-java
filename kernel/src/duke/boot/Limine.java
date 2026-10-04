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

    public static boolean baseRevisionSupported() {
        return BASE_REVISION[2] == 0;
    }

    /** The revision Limine actually loaded us with. */
    public static long loadedBaseRevision() {
        return BASE_REVISION[1];
    }

    /** Virtual address of physical address 0 in the higher-half direct map. */
    public static long hhdmOffset() {
        return Magic.peekLong(response(HHDM) + 8);
    }

    public static int memoryMapSize() {
        return (int) Magic.peekLong(response(MEMMAP) + 8);
    }

    public static long memoryMapBase(int index) {
        return Magic.peekLong(memoryMapEntry(index));
    }

    public static long memoryMapLength(int index) {
        return Magic.peekLong(memoryMapEntry(index) + 8);
    }

    public static int memoryMapType(int index) {
        return (int) Magic.peekLong(memoryMapEntry(index) + 16);
    }

    public static String memoryMapTypeName(int type) {
        return type >= 0 && type < MEMMAP_TYPES.length ? MEMMAP_TYPES[type] : "type " + type;
    }

    public static long kernelPhysicalBase() {
        return Magic.peekLong(response(EXECUTABLE_ADDRESS) + 8);
    }

    public static long kernelVirtualBase() {
        return Magic.peekLong(response(EXECUTABLE_ADDRESS) + 16);
    }

    /** The first framebuffer's address, or 0 without one (QEMU's -display none still has one). */
    public static long framebufferAddress() {
        long fb = firstFramebuffer();
        return fb == 0 ? 0 : Magic.peekLong(fb);
    }

    public static long framebufferWidth() {
        return Magic.peekLong(firstFramebuffer() + 8);
    }

    public static long framebufferHeight() {
        return Magic.peekLong(firstFramebuffer() + 16);
    }

    public static long framebufferPitch() {
        return Magic.peekLong(firstFramebuffer() + 24);
    }

    public static int framebufferBitsPerPixel() {
        return Magic.peekShort(firstFramebuffer() + 32) & 0xFFFF;
    }

    /** RSDP address as Limine reports it, or 0 without ACPI. */
    public static long rsdp() {
        long response = RSDP[RESPONSE];
        return response == 0 ? 0 : Magic.peekLong(response + 8);
    }

    private static long firstFramebuffer() {
        long response = FRAMEBUFFER[RESPONSE];
        if (response == 0 || Magic.peekLong(response + 8) == 0) {
            return 0;
        }
        return Magic.peekLong(Magic.peekLong(response + 16));
    }

    private static long memoryMapEntry(int index) {
        return Magic.peekLong(Magic.peekLong(response(MEMMAP) + 16) + 8L * index);
    }

    private static long response(long[] request) {
        long response = request[RESPONSE];
        if (response == 0) {
            throw new IllegalStateException("Limine did not answer request 0x" + Long.toHexString(request[2]));
        }
        return response;
    }
}
