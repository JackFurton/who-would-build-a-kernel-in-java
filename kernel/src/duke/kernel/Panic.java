package duke.kernel;

import duke.rt.Magic;

public final class Panic {

    private Panic() {
    }

    public static void panic(String message) {
        Console.print("\nPANIC: ");
        Console.println(message);
        haltForever();
    }

    public static void panic(String... parts) {
        Console.print("\nPANIC: ");
        for (String part : parts) {
            Console.print(part);
        }
        Console.println("");
        haltForever();
    }

    public static void panic(String message, int a, int b) {
        Console.print("\nPANIC: ");
        Console.print(message);
        Console.print(" (");
        Console.print(a);
        Console.print(", ");
        Console.print(b);
        Console.println(")");
        haltForever();
    }

    public static void haltForever() {
        Magic.disableInterrupts();
        while (true) {
            Magic.halt();
        }
    }
}
