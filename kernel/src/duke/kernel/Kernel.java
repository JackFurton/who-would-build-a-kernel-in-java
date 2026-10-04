package duke.kernel;

public final class Kernel {

    private Kernel() {
    }

    /** Called by the compiler-generated entry stub once every class initializer has run. */
    public static void main() {
        Serial.init();
        Console.println("Duke: hello from Java on bare metal");
        Console.println("bytecode arithmetic check: 6 * 7 = " + 6L * multiplier());
        Console.println("DUKE-BOOT-OK");
    }

    private static int multiplier() {
        return 7;
    }
}
