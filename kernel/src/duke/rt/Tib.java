package duke.rt;

/**
 * Reads type information blocks. A TIB is also the java.lang.Class object for its type, so
 * {@code Tib.of(o)} and {@code o.getClass()} are the same address. Offsets must match
 * duke.compiler.Layouts.
 */
public final class Tib {

    public static final int SUPER = 8;
    public static final int SIZE = 16;
    public static final int FLAGS = 20;
    public static final int ELEMENT = 24;
    public static final int NAME = 32;
    public static final int INTERFACES = 40;

    public static final int FLAG_ARRAY = 1;
    public static final int FLAG_INTERFACE = 2;
    public static final int FLAG_REFERENCE_ARRAY = 4;

    public static final int ARRAY_LENGTH = 8;
    public static final int ARRAY_DATA = 16;

    private Tib() {
    }

    public static long of(Object object) {
        return Magic.peekLong(Magic.addressOf(object));
    }

    public static long superOf(long tib) {
        return Magic.peekLong(tib + SUPER);
    }

    public static int flags(long tib) {
        return Magic.peekInt(tib + FLAGS);
    }

    /** Element size for arrays, instance size otherwise. */
    public static int size(long tib) {
        return Magic.peekInt(tib + SIZE);
    }

    public static long element(long tib) {
        return Magic.peekLong(tib + ELEMENT);
    }

    // The cast hits the compiler's exact-TIB fast path, so it never recurses into Types.checkCast.
    public static String name(long tib) {
        return (String) Magic.toObject(Magic.peekLong(tib + NAME));
    }

    public static boolean isArray(long tib) {
        return (flags(tib) & FLAG_ARRAY) != 0;
    }

    public static int arrayLength(Object array) {
        return Magic.peekInt(Magic.addressOf(array) + ARRAY_LENGTH);
    }

    public static boolean isAssignable(long from, long to) {
        if (from == to) {
            return true;
        }
        int toFlags = flags(to);
        if ((toFlags & FLAG_INTERFACE) != 0) {
            long list = Magic.peekLong(from + INTERFACES);
            if (list != 0) {
                for (long entry = list; Magic.peekLong(entry) != 0; entry += 8) {
                    if (Magic.peekLong(entry) == to) {
                        return true;
                    }
                }
            }
            return false;
        }
        if ((toFlags & FLAG_ARRAY) != 0) {
            // Distinct primitive array types are never assignable; reference arrays are covariant.
            if ((flags(from) & FLAG_REFERENCE_ARRAY) == 0 || (toFlags & FLAG_REFERENCE_ARRAY) == 0) {
                return false;
            }
            return isAssignable(element(from), element(to));
        }
        for (long t = from; t != 0; t = superOf(t)) {
            if (t == to) {
                return true;
            }
        }
        return false;
    }
}
