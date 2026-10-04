package duke.ktest;

import static duke.ktest.Assert.assertTrue;

import duke.kernel.time.HpetClock;
import duke.kernel.time.Timer;
import duke.rt.Magic;

final class TimerTest {

    static void testInterruptsAreEnabled() {
        assertTrue((Magic.flags() & 0x200) != 0, "RFLAGS.IF");
    }

    static void testTicksAdvance() {
        long before = Timer.ticks();
        HpetClock.spinNanos(50_000_000);
        assertTrue(Timer.ticks() > before, "ticks moved during 50 ms of spinning");
    }

    // The tick is calibrated against the HPET, so both clocks must agree on how long a sleep took.
    static void testTickRateMatchesTheHpet() {
        long hpetBefore = HpetClock.nanos();
        long ticksBefore = Timer.ticks();
        Timer.sleep(300);
        long hpetMillis = (HpetClock.nanos() - hpetBefore) / 1_000_000;
        long tickMillis = (Timer.ticks() - ticksBefore) * 1000 / Timer.HZ;
        assertTrue(hpetMillis >= 290, "slept at least ~300 ms by the HPET: " + hpetMillis);
        long error = Math.abs(hpetMillis - tickMillis) * 100 / hpetMillis;
        assertTrue(error <= 10, "tick " + tickMillis + " ms vs HPET " + hpetMillis + " ms");
    }
}
