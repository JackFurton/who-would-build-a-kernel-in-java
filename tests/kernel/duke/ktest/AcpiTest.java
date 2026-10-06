package duke.ktest;

import static duke.ktest.Assert.assertEquals;
import static duke.ktest.Assert.assertTrue;

import duke.kernel.acpi.Acpi;
import duke.kernel.acpi.Hpet;
import duke.kernel.acpi.Madt;
import duke.kernel.mm.KernelAddressSpace;
import duke.rt.Magic;

final class AcpiTest {

    static void testFoundTheTablesQemuProvides() {
        assertTrue(Acpi.revision() >= 2, "ACPI 2.0+ under OVMF");
        assertTrue(Acpi.find("APIC") != 0, "MADT");
        assertTrue(Acpi.find("FACP") != 0, "FADT");
        assertEquals(0, Acpi.find("NOPE"), "missing table");
    }

    static void testMadtMatchesQemuSmp() {
        assertEquals(4, Madt.cpus().size(), "tools/qemu.sh runs -smp 4");
        assertEquals(1, Madt.ioApics().size(), "one I/O APIC");
        assertEquals(2, Madt.gsiForIrq(0), "the PIT's IRQ 0 is wired to GSI 2 on q35");
        assertEquals(1, Madt.gsiForIrq(1), "the keyboard's IRQ 1 isn't overridden");
    }

    // The BSP reads its own local APIC: the ID register must name one of the MADT's CPUs.
    static void testLocalApicIdMatchesTheMadt() {
        long lapic = KernelAddressSpace.mapDevice(Madt.localApicAddress(), 4096);
        int id = Magic.peekInt(lapic + 0x20) >>> 24;
        assertEquals(Madt.cpus().get(0).apicId, id, "BSP APIC id");
        int version = Magic.peekInt(lapic + 0x30) & 0xFF;
        assertTrue(version >= 0x10, "integrated APIC, version 0x" + Integer.toHexString(version));
    }

    static void testHpetReportsAClockPeriod() {
        assertTrue(Hpet.address() != 0, "HPET present");
        long hpet = KernelAddressSpace.mapDevice(Hpet.address(), 1024);
        long capabilities = Magic.peekLong(hpet);
        long femtosecondsPerTick = capabilities >>> 32;
        assertTrue(femtosecondsPerTick > 0 && femtosecondsPerTick <= 100_000_000L, "period " + femtosecondsPerTick + " fs");
    }
}
