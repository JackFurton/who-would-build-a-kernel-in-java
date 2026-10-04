package java.lang;

import duke.rt.Magic;
import duke.rt.Tib;

/**
 * Class objects are the compiler's type information blocks: the TIB for a type starts with an
 * object header naming Class, so a TIB address is a valid Class reference. That's why this class
 * has no fields and reads everything through duke.rt.Tib.
 */
public final class Class<T> {

    private Class() {
    }

    private long tib() {
        return Magic.addressOf(this);
    }

    public String getName() {
        return Tib.name(tib());
    }

    /** Primitive arrays have no component Class yet, so their simple name falls back to getName(). */
    public String getSimpleName() {
        String name = getName();
        if (isArray() && getComponentType() != null) {
            return getComponentType().getSimpleName().concat("[]");
        }
        int start = Math.max(name.lastIndexOf('.'), name.lastIndexOf('$')) + 1;
        return name.substring(start);
    }

    public boolean isArray() {
        return (Tib.flags(tib()) & Tib.FLAG_ARRAY) != 0;
    }

    public boolean isInterface() {
        return (Tib.flags(tib()) & Tib.FLAG_INTERFACE) != 0;
    }

    /** Null for primitive arrays until primitive Class objects exist. */
    public Class<?> getComponentType() {
        if (!isArray()) {
            return null;
        }
        return toClass(Tib.element(tib()));
    }

    public Class<? super T> getSuperclass() {
        if (isInterface()) {
            return null;
        }
        return castSuper(Tib.superOf(tib()));
    }

    public boolean isInstance(Object object) {
        return object != null && Tib.isAssignable(Tib.of(object), tib());
    }

    public boolean isAssignableFrom(Class<?> other) {
        return Tib.isAssignable(other.tib(), tib());
    }

    @Override
    public String toString() {
        return (isInterface() ? "interface " : "class ").concat(getName());
    }

    private static Class<?> toClass(long tib) {
        // Not a ?: with a null arm: javac would go looking for the box classes (#39).
        if (tib == 0) {
            return null;
        }
        return (Class<?>) Magic.toObject(tib);
    }

    private static <S> Class<S> castSuper(long tib) {
        return (Class<S>) toClass(tib);
    }
}
