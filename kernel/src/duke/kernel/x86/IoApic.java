package duke.kernel.x86;

import duke.kernel.acpi.Madt;
import duke.kernel.mm.KernelAddressSpace;
import duke.rt.Magic;

/** Routes ISA IRQs to this CPU through the (first) I/O APIC, honouring the MADT's overrides. */
public final class IoApic {

    private static final int SELECT = 0x00;
    private static final int WINDOW = 0x10;
    private static final int REDIRECTION = 0x10;
    private static final int ACTIVE_LOW = 1 << 13;
    private static final int LEVEL = 1 << 15;
    private static final int MASKED = 1 << 16;

    private static long base;
    private static int gsiBase;

    private IoApic() {
    }

    public static void init() {
        Madt.IoApic io = Madt.ioApics().get(0);
        base = KernelAddressSpace.mapDevice(io.address, 4096);
        gsiBase = io.gsiBase;
    }

    /** Sends ISA {@code irq} to {@code vector} on this CPU. ISA defaults: edge-triggered, active high. */
    public static void routeIrq(int irq, int vector) {
        int gsi = Madt.gsiForIrq(irq);
        int low = vector;
        for (Madt.Override o : Madt.overrides()) {
            if (o.irq == irq) {
                low |= (o.activeLow() ? ACTIVE_LOW : 0) | (o.levelTriggered() ? LEVEL : 0);
            }
        }
        int entry = REDIRECTION + 2 * (gsi - gsiBase);
        write(entry + 1, LocalApic.id() << 24);
        write(entry, low & ~MASKED);
    }

    private static void write(int register, int value) {
        Magic.pokeInt(base + SELECT, register);
        Magic.pokeInt(base + WINDOW, value);
    }
}
