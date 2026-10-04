package duke.ktest;

import duke.kernel.Console;

/** The host runner checks the serial output between these markers verbatim. */
final class ConsoleTest {

    static void testPrintHex() {
        Console.print("\nCONSOLE-HEX-BEGIN\n");
        printLine(0L);
        printLine(1L);
        printLine(0x0123_4567_89ab_cdefL);
        printLine(0xfedc_ba98_7654_3210L);
        printLine(-1L);
        printLine(Long.MIN_VALUE);
        printLine(Long.MAX_VALUE);
        Console.println("CONSOLE-HEX-END");
    }

    private static void printLine(long value) {
        Console.printHex(value);
        Console.print("\n");
    }
}
