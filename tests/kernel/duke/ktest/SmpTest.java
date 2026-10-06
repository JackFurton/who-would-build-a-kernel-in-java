package duke.ktest;

import static duke.ktest.Assert.assertEquals;
import static duke.ktest.Assert.assertTrue;

import duke.kernel.Smp;
import duke.kernel.acpi.Madt;

final class SmpTest {

    static void testEveryCpuCheckedIn() {
        assertEquals(Madt.cpus().size(), Smp.cpuCount(), "Limine started every CPU the MADT lists");
        assertEquals(Smp.cpuCount(), Smp.onlineCount(), "every CPU checked in");
    }

    static void testEachCpuHasItsOwnApic() {
        for (int i = 0; i < Smp.cpuCount(); i++) {
            for (int j = i + 1; j < Smp.cpuCount(); j++) {
                assertTrue(Smp.apicId(i) != Smp.apicId(j), "cpus " + i + " and " + j + " share an APIC id");
            }
        }
    }
}
