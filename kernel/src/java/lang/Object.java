package java.lang;

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
}
