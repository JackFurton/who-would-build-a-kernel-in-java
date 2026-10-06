package duke.ktest;

import static duke.ktest.Assert.assertEquals;
import static duke.ktest.Assert.assertTrue;

import duke.rt.Magic;

final class CpuBlockTest {

    private static final int IA32_GS_BASE = 0xC0000101;

    static void testGsPointsAtTheCpuBlock() {
        long block = Magic.cpuBlock();
        assertTrue(block != 0, "the block knows its own address");
        assertEquals(block, Magic.readMsr(IA32_GS_BASE), "GS base");
    }

    // The prologue's stack check reads the limit through GS, so the block must hold the real one.
    static void testStackStateLivesInTheBlock() {
        long block = Magic.cpuBlock();
        assertEquals(Magic.stackLimit(), Magic.peekLong(block + 8), "stack limit");
        assertEquals(Magic.stackBase(), Magic.peekLong(block + 24), "stack base");
        assertTrue(Magic.stackLimit() > Magic.stackBase(), "the limit sits above the base, past the reserve");
    }
}
