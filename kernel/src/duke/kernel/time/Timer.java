package duke.kernel.time;

import duke.kernel.Scheduler;
import duke.kernel.x86.Interrupts;
import duke.kernel.x86.LocalApic;
import duke.kernel.x86.LocalApicTimer;
import duke.rt.Magic;

/**
 * The periodic tick: every CPU's local APIC timer, calibrated against the HPET, interrupting HZ
 * times a second. The handler runs with interrupts off and must not allocate. Time itself comes
 * from the HPET, not from counting interrupts, so whichever CPU's timer fires first after a
 * sleeper's deadline wakes it, rather than always the CPU that counts.
 */
public final class Timer {

    public static final int HZ = 100;
    public static final int VECTOR = 0x20;
    private static final long CALIBRATION_NANOS = 10_000_000;

    private static long apicTicksPerSecond;

    private Timer() {
    }

    public static void init() {
        HpetClock.init();
        apicTicksPerSecond = LocalApicTimer.calibrate(CALIBRATION_NANOS) * (1_000_000_000L / CALIBRATION_NANOS);
        Interrupts.register(VECTOR, frame -> {
            LocalApic.endOfInterrupt();
            Scheduler.tick();
        });
        startOnThisCpu();
    }

    /** Starts the calling CPU's tick. Local APIC timers all run off the bus clock {@link #init} measured. */
    public static void startOnThisCpu() {
        LocalApicTimer.startPeriodic(VECTOR, apicTicksPerSecond / HZ);
    }

    public static long ticks() {
        return HpetClock.nanos() / (1_000_000_000 / HZ);
    }

    public static long uptimeMillis() {
        return HpetClock.nanos() / 1_000_000;
    }

    /** Local APIC timer ticks per second, as measured against the HPET. */
    public static long apicFrequency() {
        return apicTicksPerSecond;
    }

    /** Sleeps at least {@code millis} by halting between ticks. Interrupts must be enabled. */
    public static void sleep(long millis) {
        long until = HpetClock.nanos() + millis * 1_000_000;
        while (HpetClock.nanos() < until) {
            Magic.halt();
        }
    }
}
