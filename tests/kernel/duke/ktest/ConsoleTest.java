package duke.ktest;

import static duke.ktest.Assert.assertEquals;

import duke.kernel.Console;

/** Exercises the formatter used by printHex inside the compiled kernel. */
final class ConsoleTest {

    static void testHexZeroPadding() {
        assertEquals("0x0000000000000000", Console.hex(0L), "zero");
        assertEquals("0x0000000000000001", Console.hex(1L), "one");
    }

    static void testHexEveryDigit() {
        assertEquals("0x0123456789abcdef", Console.hex(0x0123_4567_89ab_cdefL), "ascending digits");
        assertEquals("0xfedcba9876543210", Console.hex(0xfedc_ba98_7654_3210L), "descending digits");
    }

    static void testHexSignedBoundaries() {
        assertEquals("0xffffffffffffffff", Console.hex(-1L), "minus one");
        assertEquals("0x8000000000000000", Console.hex(Long.MIN_VALUE), "minimum long");
        assertEquals("0x7fffffffffffffff", Console.hex(Long.MAX_VALUE), "maximum long");
    }

    static void testHexResultsAreIndependent() {
        String first = Console.hex(1L);
        assertEquals("0xffffffffffffffff", Console.hex(-1L), "next call");
        assertEquals("0x0000000000000001", first, "previous result");
        assertEquals(first, Console.hex(1L), "repeated call");
    }

    static void testPrintHex() {
        // Keep the output path reachable so dukec compiles printHex as well as hex.
        Console.printHex(0x0123_4567_89ab_cdefL);
    }
}
