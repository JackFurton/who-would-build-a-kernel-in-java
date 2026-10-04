package duke.kernel.acpi;

import duke.boot.Limine;
import duke.kernel.mm.PhysicalMemory;
import duke.rt.Magic;
import java.util.ArrayList;
import java.util.List;

/**
 * ACPI table discovery: RSDP from Limine, then the XSDT (or the RSDT on ACPI 1.0), with every
 * table's checksum verified. Tables are read through the direct map, which covers the ACPI
 * reclaimable and NVS regions they live in.
 */
public final class Acpi {

    private static final int HEADER_LENGTH = 36;

    private static int revision;
    private static final List<Long> tables = new ArrayList<>();

    private Acpi() {
    }

    public static boolean available() {
        return Limine.rsdp() != 0;
    }

    public static void init() {
        long rsdp = Limine.rsdp();
        if (rsdp == 0) {
            return;
        }
        if (!signature(rsdp, 8).equals("RSD PTR ") || checksum(rsdp, 20) != 0) {
            throw new IllegalStateException("bad RSDP at 0x" + Long.toHexString(rsdp));
        }
        revision = Magic.peekByte(rsdp + 15) & 0xFF;
        boolean extended = revision >= 2;
        long root = PhysicalMemory.toVirtual(extended ? Magic.peekLong(rsdp + 24) : Magic.peekInt(rsdp + 16) & 0xFFFF_FFFFL);
        verify(root, extended ? "XSDT" : "RSDT");
        int entrySize = extended ? 8 : 4;
        int count = (length(root) - HEADER_LENGTH) / entrySize;
        for (int i = 0; i < count; i++) {
            long entry = root + HEADER_LENGTH + (long) i * entrySize;
            long physical = extended ? Magic.peekLong(entry) : Magic.peekInt(entry) & 0xFFFF_FFFFL;
            long table = PhysicalMemory.toVirtual(physical);
            if (checksum(table, length(table)) == 0) {
                tables.add(table);
            }
        }
    }

    /** 0 for ACPI 1.0, 2 or more for ACPI 2.0+. */
    public static int revision() {
        return revision;
    }

    /** Virtual address of the first table with this signature, or 0. */
    public static long find(String signature) {
        for (long table : tables) {
            if (signature(table, 4).equals(signature)) {
                return table;
            }
        }
        return 0;
    }

    public static List<String> signatures() {
        List<String> names = new ArrayList<>();
        for (long table : tables) {
            names.add(signature(table, 4));
        }
        return names;
    }

    static int length(long table) {
        return Magic.peekInt(table + 4);
    }

    private static void verify(long table, String expected) {
        if (!signature(table, 4).equals(expected) || checksum(table, length(table)) != 0) {
            throw new IllegalStateException("bad " + expected + " at 0x" + Long.toHexString(table));
        }
    }

    static String signature(long address, int length) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            sb.append((char) (Magic.peekByte(address + i) & 0xFF));
        }
        return sb.toString();
    }

    /** ACPI checksums make the bytes of a structure sum to 0 modulo 256. */
    static int checksum(long address, int length) {
        int sum = 0;
        for (int i = 0; i < length; i++) {
            sum += Magic.peekByte(address + i);
        }
        return sum & 0xFF;
    }
}
