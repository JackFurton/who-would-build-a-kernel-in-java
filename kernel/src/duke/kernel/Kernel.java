package duke.kernel;

import duke.kernel.x86.Idt;
import duke.kernel.x86.Interrupts;
import duke.rt.Magic;

public final class Kernel {

    private Kernel() {
    }

    /** Called by the compiler-generated entry stub once every class initializer has run. */
    private static int breakpoints;

    public static void main() {
        Serial.init();
        Idt.load();
        Console.println("Duke: hello from Java on bare metal");
        Console.println("bytecode arithmetic check: 6 * 7 = " + 6L * multiplier());
        Interrupts.register(3, frame -> breakpoints++);
        Magic.breakpoint();
        Magic.breakpoint();
        Console.println("interrupts: IDT loaded, " + breakpoints + " breakpoints handled and resumed");
        Console.println("DUKE-BOOT-OK");
    }

    private static int multiplier() {
        return 7;
    }
}
