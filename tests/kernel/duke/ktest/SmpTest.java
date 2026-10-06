package duke.ktest;

import static duke.ktest.Assert.assertEquals;
import static duke.ktest.Assert.assertTrue;

import duke.kernel.Smp;
import duke.kernel.acpi.Madt;
import duke.rt.Magic;
import duke.rt.Tib;

final class SmpTest {

    static void testEveryCpuCheckedIn() {
        assertEquals(Madt.cpus().size(), Smp.cpuCount(), "Limine started every CPU the MADT lists");
        assertEquals(Smp.cpuCount(), Smp.onlineCount(), "every CPU checked in");
    }

    static void testEachCpuHasItsOwnApic() {
        for (int i = 0; i < Smp.cpuCount(); i++) {
            for (int j = i + 1; j < Smp.cpuCount(); j++) {
                assertTrue(Smp.apicId(i) != Smp.apicId(j), "cpus " + i + " and " + j + " share an APIC id");
            }
        }
    }

    static void testCpuIndexComesFromTheBlock() {
        assertEquals(Magic.peekLong(Magic.cpuBlock() + 32), (long) Magic.cpuIndex(), "cpu index");
    }

    static void testExchangeReturnsTheOldValue() {
        int[] word = {7};
        long address = Magic.addressOf(word) + Tib.ARRAY_DATA;
        assertEquals(7, Magic.exchangeInt(address, 1), "first exchange");
        assertEquals(1, Magic.exchangeInt(address, 0), "second exchange");
        assertEquals(0, word[0], "stored");
    }

    // Whichever CPU's timer fires first after the deadline wakes a sleeper, so a thread that
    // sleeps often lands on more than one of them.
    static void testThreadsMoveBetweenCpus() throws InterruptedException {
        if (Smp.onlineCount() < 2) {
            return;
        }
        long[] seen = new long[1];
        Thread sleeper = new Thread(() -> {
            for (int i = 0; i < 40; i++) {
                seen[0] |= 1L << Magic.cpuIndex();
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
        sleeper.start();
        sleeper.join();
        int cpus = 0;
        for (long bits = seen[0]; bits != 0; bits &= bits - 1) {
            cpus++;
        }
        assertTrue(cpus > 1, "ran on cpus 0x" + Long.toHexString(seen[0]));
    }
}
