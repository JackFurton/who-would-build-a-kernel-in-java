package duke.kernel.time;

import duke.kernel.Scheduler;
import duke.kernel.x86.Interrupts;
import duke.kernel.x86.LocalApic;
import duke.kernel.x86.LocalApicTimer;
import duke.rt.Magic;

/**
 * The periodic tick: the local APIC timer, calibrated against the HPET, interrupting HZ times a
 * second. The handler runs with interrupts off and must not allocate.
 */
public final class Timer {

    public static final int HZ = 100;
    public static final int VECTOR = 0x20;
    private static final long CALIBRATION_NANOS = 10_000_000;

    private static volatile long ticks;
    private static long apicTicksPerSecond;

    private Timer() {
    }

    public static void init() {
        HpetClock.init();
        apicTicksPerSecond = LocalApicTimer.calibrate(CALIBRATION_NANOS) * (1_000_000_000L / CALIBRATION_NANOS);
        Interrupts.register(VECTOR, frame -> {
            ticks++;
            LocalApic.endOfInterrupt();
            Scheduler.tick();
        });
        LocalApicTimer.startPeriodic(VECTOR, apicTicksPerSecond / HZ);
    }

    public static long ticks() {
        return ticks;
    }

    public static long uptimeMillis() {
        return ticks * 1000 / HZ;
    }

    /** Local APIC timer ticks per second, as measured against the HPET. */
    public static long apicFrequency() {
        return apicTicksPerSecond;
    }

    /** Sleeps at least {@code millis} by halting between ticks. Interrupts must be enabled. */
    public static void sleep(long millis) {
        long until = ticks + (millis * HZ + 999) / 1000;
        while (ticks < until) {
            Magic.halt();
        }
    }
}
