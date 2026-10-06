package java.lang;

import duke.kernel.Monitors;
import duke.rt.Heap;
import duke.rt.Magic;

public class Object {

    public Object() {
    }

    public final Class<?> getClass() {
        return (Class<?>) Magic.toObject(Magic.peekLong(Magic.addressOf(this)));
    }

    public boolean equals(Object other) {
        return this == other;
    }

    public int hashCode() {
        return Heap.identityHash(this);
    }

    public String toString() {
        return getClass().getName().concat("@").concat(Integer.toHexString(hashCode()));
    }

    public final void wait() throws InterruptedException {
        Monitors.await(this, 0);
    }

    public final void wait(long timeoutMillis) throws InterruptedException {
        Monitors.await(this, timeoutMillis);
    }

    public final void wait(long timeoutMillis, int nanos) throws InterruptedException {
        if (timeoutMillis < 0) {
            throw new IllegalArgumentException("timeout value is negative");
        }
        if (nanos < 0 || nanos > 999_999) {
            throw new IllegalArgumentException("nanosecond timeout value out of range");
        }
        if (nanos > 0 && timeoutMillis < Long.MAX_VALUE) {
            timeoutMillis++;
        }
        Monitors.await(this, timeoutMillis);
    }

    public final void notify() {
        Monitors.notify(this, false);
    }

    public final void notifyAll() {
        Monitors.notify(this, true);
    }
}
