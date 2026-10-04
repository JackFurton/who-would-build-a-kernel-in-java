package duke.rt;

import duke.kernel.Panic;

/**
 * Subtype tests over TIBs, for checkcast, instanceof and array stores. The compiler inlines the
 * exact-match fast path and calls here for everything else.
 */
final class Types {

    // Must match duke.compiler.Layouts.
    private static final int TIB_SUPER = 0;
    private static final int TIB_FLAGS = 12;
    private static final int TIB_ELEMENT = 16;
    private static final int TIB_NAME = 24;
    private static final int TIB_INTERFACES = 32;
    private static final int FLAG_ARRAY = 1;
    private static final int FLAG_INTERFACE = 2;
    private static final int FLAG_REFERENCE_ARRAY = 4;

    private Types() {
    }

    static boolean instanceOf(Object object, long tib) {
        return object != null && isAssignable(tibOf(object), tib);
    }

    static void checkCast(Object object, long tib) {
        if (object != null && !isAssignable(tibOf(object), tib)) {
            Panic.panic("ClassCastException: ", nameOf(tibOf(object)), " cannot be cast to ", nameOf(tib));
        }
    }

    static void checkArrayStore(Object array, Object value) {
        long element = Magic.peekLong(tibOf(array) + TIB_ELEMENT);
        if (value != null && !isAssignable(tibOf(value), element)) {
            Panic.panic("ArrayStoreException: ", nameOf(tibOf(value)), " into ", nameOf(tibOf(array)));
        }
    }

    static boolean isAssignable(long from, long to) {
        if (from == to) {
            return true;
        }
        int toFlags = Magic.peekInt(to + TIB_FLAGS);
        if ((toFlags & FLAG_INTERFACE) != 0) {
            long list = Magic.peekLong(from + TIB_INTERFACES);
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
            int fromFlags = Magic.peekInt(from + TIB_FLAGS);
            if ((fromFlags & FLAG_REFERENCE_ARRAY) == 0 || (toFlags & FLAG_REFERENCE_ARRAY) == 0) {
                return false;
            }
            return isAssignable(Magic.peekLong(from + TIB_ELEMENT), Magic.peekLong(to + TIB_ELEMENT));
        }
        for (long t = from; t != 0; t = Magic.peekLong(t + TIB_SUPER)) {
            if (t == to) {
                return true;
            }
        }
        return false;
    }

    private static long tibOf(Object object) {
        return Magic.peekLong(Magic.addressOf(object));
    }

    // The cast hits the compiler's exact-TIB fast path, so it never recurses into checkCast.
    private static String nameOf(long tib) {
        return (String) Magic.toObject(Magic.peekLong(tib + TIB_NAME));
    }
}
