package duke.kernel.time;

import duke.kernel.acpi.Hpet;
import duke.kernel.mm.KernelAddressSpace;
import duke.rt.Magic;

/** The HPET's free-running main counter: the reference clock everything else is calibrated against. */
public final class HpetClock {

    private static final int CAPABILITIES = 0x000;
    private static final int CONFIGURATION = 0x010;
    private static final int MAIN_COUNTER = 0x0F0;
    private static final long ENABLE = 1;

    private static long base;
    private static long femtosecondsPerTick;

    private HpetClock() {
    }

    public static void init() {
        if (Hpet.address() == 0) {
            throw new IllegalStateException("no HPET to calibrate timers against");
        }
        base = KernelAddressSpace.mapDevice(Hpet.address(), 1024);
        femtosecondsPerTick = Magic.peekLong(base + CAPABILITIES) >>> 32;
        Magic.pokeLong(base + CONFIGURATION, Magic.peekLong(base + CONFIGURATION) | ENABLE);
    }

    /** Nanoseconds since the HPET was enabled. */
    public static long nanos() {
        // In two steps: the product alone overflows after a couple of hours at a 10 MHz HPET.
        long counter = Magic.peekLong(base + MAIN_COUNTER);
        return counter / 1_000_000 * femtosecondsPerTick + counter % 1_000_000 * femtosecondsPerTick / 1_000_000;
    }

    public static void spinNanos(long duration) {
        long until = nanos() + duration;
        while (nanos() < until) {
            Magic.pause();
        }
    }
}
