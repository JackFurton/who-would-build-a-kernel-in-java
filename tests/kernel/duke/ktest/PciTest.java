package duke.ktest;

import static duke.ktest.Assert.assertEquals;
import static duke.ktest.Assert.assertTrue;

import duke.kernel.pci.Pci;
import duke.rt.Magic;

final class PciTest {

    static void testFoundTheQ35HostBridge() {
        Pci.Function host = Pci.functions().get(0);
        assertEquals(0, host.bus, "bus");
        assertEquals(0, host.device, "device");
        assertEquals(0x8086, host.vendorId, "Intel");
        assertEquals(0x29c0, host.deviceId, "q35 host bridge");
        assertEquals(0x06, host.classCode, "bridge class");
    }

    static void testFoundTheAhciController() {
        Pci.Function ahci = Pci.find(0x01, 0x06);
        assertTrue(ahci != null, "SATA controller");
        assertEquals(0x1f, ahci.device, "device");
        assertEquals(2, ahci.function, "function");
        assertEquals(1, ahci.progIf, "AHCI");
        boolean abar = false;
        for (Pci.Bar bar : ahci.bars) {
            abar |= bar.index == 5 && !bar.io && bar.address != 0;
        }
        assertTrue(abar, "AHCI registers in BAR5");
    }

    // Config space is live: the vendor and device ids read back through the mapping.
    static void testConfigSpaceIsMapped() {
        Pci.Function host = Pci.functions().get(0);
        assertEquals(0x29c08086, Magic.peekInt(host.config), "ids");
        assertTrue(Pci.find(0x7f, 0x7f) == null, "missing class");
    }
}
