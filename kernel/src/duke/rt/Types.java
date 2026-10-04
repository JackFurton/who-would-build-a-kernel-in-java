package duke.rt;

import duke.kernel.Panic;

/** Slow paths for checkcast, instanceof and array stores; the compiler inlines exact matches. */
final class Types {

    private Types() {
    }

    static boolean instanceOf(Object object, long tib) {
        return object != null && Tib.isAssignable(Tib.of(object), tib);
    }

    static void checkCast(Object object, long tib) {
        if (object != null && !Tib.isAssignable(Tib.of(object), tib)) {
            Panic.panic("ClassCastException: ", Tib.name(Tib.of(object)), " cannot be cast to ", Tib.name(tib));
        }
    }

    static void checkArrayStore(Object array, Object value) {
        long element = Tib.element(Tib.of(array));
        if (value != null && !Tib.isAssignable(Tib.of(value), element)) {
            Panic.panic("ArrayStoreException: ", Tib.name(Tib.of(value)), " into ", Tib.name(Tib.of(array)));
        }
    }
}
