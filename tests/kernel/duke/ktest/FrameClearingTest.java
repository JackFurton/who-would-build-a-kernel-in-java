package duke.ktest;

import static duke.ktest.Assert.assertEquals;
import static duke.ktest.Assert.assertTrue;

import duke.kernel.mm.KernelStacks;
import duke.kernel.mm.PhysicalMemory;
import duke.rt.Magic;

final class FrameClearingTest {

    static void testZeroedFramesAreClearThroughTheLastWord() {
        long frame = PhysicalMemory.tryAllocateZeroed();
        assertTrue(frame >= 0, "a free frame is available");
        try {
            long base = PhysicalMemory.toVirtual(frame);
            for (long offset = 0; offset < PhysicalMemory.PAGE_SIZE; offset += 8) {
                assertEquals(0, Magic.peekLong(base + offset), "zeroed frame word");
                Magic.pokeLong(base + offset, -1L);
            }
        } finally {
            PhysicalMemory.free(frame);
        }
    }

    static void testReusedStackSlotsAreZeroed() {
        boolean interrupts = (Magic.flags() & 0x200) != 0;
        Magic.disableInterrupts();
        try {
            for (int round = 0; round < 3; round++) {
                long stack = KernelStacks.allocate();
                assertTrue(stack != -1, "stack allocation");
                try {
                    for (int offset = 0; offset < KernelStacks.STACK_BYTES; offset += 8) {
                        assertEquals(0, Magic.peekLong(stack + offset), "fresh stack word");
                        Magic.pokeLong(stack + offset, -1L);
                    }
                } finally {
                    KernelStacks.free(stack);
                }
            }
        } finally {
            if (interrupts) {
                Magic.enableInterrupts();
            }
        }
    }
}
