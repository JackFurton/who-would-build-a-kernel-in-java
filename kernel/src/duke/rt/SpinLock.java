package duke.rt;

/**
 * Test-and-test-and-set spinlocks on an {@code int[]} whose first element is the lock word:
 * build-time static initializers can make arrays but not objects, so a lock can be a constant.
 *
 * <p>Two kinds. {@link #lock} leaves interrupts alone and its spin is a safepoint, for locks held
 * across code that can stop for a collection (the heap's). {@link #lockInterruptsOff} turns
 * interrupts off first, for short sections that never allocate: nothing waits on it at a safepoint,
 * so a holder can't be stopped and a spinner never needs to be.
 */
public final class SpinLock {

    private static final long INTERRUPT_FLAG = 1 << 9;

    private SpinLock() {
    }

    public static void lock(int[] lock) {
        long word = Magic.addressOf(lock) + Tib.ARRAY_DATA;
        while (Magic.exchangeInt(word, 1) != 0) {
            while (lock[0] != 0) {
                Magic.pause();
            }
        }
    }

    public static void unlock(int[] lock) {
        lock[0] = 0;
    }

    /** Returns the flags to give {@link #unlockInterruptsOff}. */
    public static long lockInterruptsOff(int[] lock) {
        long flags = Magic.flags();
        Magic.disableInterrupts();
        lock(lock);
        return flags;
    }

    /** Releases, and turns interrupts back on if {@code flags} had them on. */
    public static void unlockInterruptsOff(int[] lock, long flags) {
        lock[0] = 0;
        restoreInterrupts(flags);
    }

    public static void restoreInterrupts(long flags) {
        if ((flags & INTERRUPT_FLAG) != 0) {
            Magic.enableInterrupts();
        }
    }

    public static boolean interruptsOn(long flags) {
        return (flags & INTERRUPT_FLAG) != 0;
    }
}
