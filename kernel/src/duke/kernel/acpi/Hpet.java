package duke.kernel.acpi;

import duke.rt.Magic;

/** The HPET table: just the physical address of the timer's registers. */
public final class Hpet {

    private static long address;

    private Hpet() {
    }

    public static void init() {
        long table = Acpi.find("HPET");
        if (table != 0 && Magic.peekByte(table + 40) == 0) {
            // A Generic Address Structure at 40; address space 0 means memory-mapped.
            address = Magic.peekLong(table + 44);
        }
    }

    /** Physical address of the HPET registers, or 0 without one. */
    public static long address() {
        return address;
    }
}
