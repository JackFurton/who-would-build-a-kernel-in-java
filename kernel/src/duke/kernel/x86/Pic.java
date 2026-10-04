package duke.kernel.x86;

import duke.rt.Magic;

/**
 * The legacy 8259 PICs. Limine masks them, but a masked PIC can still raise a spurious IRQ 7 or
 * 15, and on its power-on vector base that would land on a CPU exception vector. Remapping them
 * to 0xE0-0xEF and masking everything keeps them out of the way of the APIC.
 */
public final class Pic {

    public static final int VECTOR_BASE = 0xE0;

    private static final int MASTER_COMMAND = 0x20;
    private static final int MASTER_DATA = 0x21;
    private static final int SLAVE_COMMAND = 0xA0;
    private static final int SLAVE_DATA = 0xA1;

    private Pic() {
    }

    public static void disable() {
        // ICW1: initialize, expect ICW4. ICW2: vector bases. ICW3: cascade on IRQ 2. ICW4: 8086 mode.
        Magic.outb(MASTER_COMMAND, 0x11);
        Magic.outb(SLAVE_COMMAND, 0x11);
        Magic.outb(MASTER_DATA, VECTOR_BASE);
        Magic.outb(SLAVE_DATA, VECTOR_BASE + 8);
        Magic.outb(MASTER_DATA, 4);
        Magic.outb(SLAVE_DATA, 2);
        Magic.outb(MASTER_DATA, 1);
        Magic.outb(SLAVE_DATA, 1);
        Magic.outb(MASTER_DATA, 0xFF);
        Magic.outb(SLAVE_DATA, 0xFF);
        for (int irq = 0; irq < 16; irq++) {
            Interrupts.register(VECTOR_BASE + irq, frame -> { });
        }
    }
}
