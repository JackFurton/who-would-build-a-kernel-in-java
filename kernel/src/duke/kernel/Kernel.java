package duke.kernel;

import duke.boot.Limine;
import duke.kernel.mm.PhysicalMemory;
import duke.kernel.x86.Idt;
import duke.kernel.x86.Interrupts;
import duke.rt.Magic;

public final class Kernel {

    private Kernel() {
    }

    private static int breakpoints;

    /** Brings up the core subsystems. Shared with the ktest runner, so tests see a real kernel. */
    public static void init() {
        Serial.init();
        Idt.load();
        if (!Limine.baseRevisionSupported()) {
            Panic.panic("Limine doesn't support base revision 6");
        }
        PhysicalMemory.init();
    }

    /** The entry point: the compiler's _start stub calls this. */
    public static void main() {
        init();
        Console.println("Duke: hello from Java on bare metal");
        Console.println("bytecode arithmetic check: 6 * 7 = " + 6L * multiplier());
        Interrupts.register(3, frame -> breakpoints++);
        Magic.breakpoint();
        Magic.breakpoint();
        Console.println("interrupts: IDT loaded, " + breakpoints + " breakpoints handled and resumed");
        try {
            Integer.parseInt("forty-two");
        } catch (NumberFormatException e) {
            Console.println("exceptions: caught " + e);
        }
        printMemoryMap();
        Console.println("frames: " + PhysicalMemory.freeFrames() + " free ("
                + (PhysicalMemory.freeFrames() * PhysicalMemory.PAGE_SIZE >> 20) + " MiB)");
        Console.println("DUKE-BOOT-OK");
    }

    private static void printMemoryMap() {
        Console.println("boot: Limine base revision " + Limine.loadedBaseRevision() + ", hhdm at 0x"
                + Long.toHexString(Limine.hhdmOffset()) + ", kernel at physical 0x"
                + Long.toHexString(Limine.kernelPhysicalBase()));
        long usable = 0;
        for (int i = 0; i < Limine.memoryMapSize(); i++) {
            long base = Limine.memoryMapBase(i);
            long length = Limine.memoryMapLength(i);
            int type = Limine.memoryMapType(i);
            if (type == Limine.MEMMAP_USABLE) {
                usable += length;
            }
            Console.println("  0x" + Long.toHexString(base) + "-0x" + Long.toHexString(base + length) + " "
                    + Limine.memoryMapTypeName(type));
        }
        Console.println("memory: " + Limine.memoryMapSize() + " regions, " + (usable >> 20) + " MiB usable");
    }

    private static int multiplier() {
        return 7;
    }
}
