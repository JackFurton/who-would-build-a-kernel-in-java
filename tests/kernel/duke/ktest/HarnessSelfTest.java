package duke.ktest;

import static duke.ktest.Assert.assertEquals;
import static duke.ktest.Assert.assertThrows;
import static duke.ktest.Assert.assertTrue;

import duke.boot.Limine;

/** Proves the runner itself works, and that Limine answered every request we rely on. */
final class HarnessSelfTest {

    static void testAssertionsPass() {
        assertEquals(4, 2 + 2, "arithmetic");
        assertTrue("duke".startsWith("du"), "strings");
    }

    static void testAssertThrowsCatches() {
        ArithmeticException e = assertThrows(ArithmeticException.class, () -> {
            int zero = 0;
            int x = 1 / zero;
        }, "division");
        assertEquals("/ by zero", e.getMessage(), "message");
    }

    static void testLimineAnsweredRequests() {
        assertTrue(Limine.baseRevisionSupported(), "base revision 6");
        assertTrue(Limine.hhdmOffset() != 0, "hhdm");
        assertTrue(Limine.memoryMapSize() > 0, "memory map");
        assertTrue(Limine.kernelPhysicalBase() != 0, "executable address");
    }
}
