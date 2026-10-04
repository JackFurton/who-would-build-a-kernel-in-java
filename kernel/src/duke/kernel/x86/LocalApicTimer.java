package duke.kernel.x86;

import duke.kernel.time.HpetClock;

/** Calibrating and starting the local APIC timer; split out so time code doesn't reach into LocalApic. */
public final class LocalApicTimer {

    private LocalApicTimer() {
    }

    /** Timer ticks elapsed while the HPET measures {@code nanos}. */
    public static long calibrate(long nanos) {
        LocalApic.startCountdown();
        HpetClock.spinNanos(nanos);
        return LocalApic.elapsedSinceCountdown();
    }

    public static void startPeriodic(int vector, long ticks) {
        LocalApic.startPeriodic(vector, ticks);
    }
}
