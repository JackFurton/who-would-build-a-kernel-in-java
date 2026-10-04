package java.lang;

import duke.kernel.Panic;
import duke.rt.Heap;
import duke.rt.Magic;
import duke.rt.Tib;

public final class System {

    private System() {
    }

    /** JDK semantics, including copying the prefix before an incompatible element of a reference array. */
    public static void arraycopy(Object src, int srcPos, Object dest, int destPos, int length) {
        if (src == null || dest == null) {
            Panic.panic("NullPointerException: arraycopy");
        }
        long srcTib = Tib.of(src);
        long destTib = Tib.of(dest);
        if (!Tib.isArray(srcTib) || !Tib.isArray(destTib)) {
            Panic.panic("ArrayStoreException: arraycopy: not an array");
        }
        boolean srcRefs = (Tib.flags(srcTib) & Tib.FLAG_REFERENCE_ARRAY) != 0;
        boolean destRefs = (Tib.flags(destTib) & Tib.FLAG_REFERENCE_ARRAY) != 0;
        if (srcRefs != destRefs || !srcRefs && srcTib != destTib) {
            Panic.panic("ArrayStoreException: arraycopy: type mismatch: ", Tib.name(srcTib), " into ", Tib.name(destTib));
        }
        if (srcPos < 0 || destPos < 0 || length < 0
                || srcPos > Tib.arrayLength(src) - length || destPos > Tib.arrayLength(dest) - length) {
            Panic.panic("ArrayIndexOutOfBoundsException: arraycopy", srcPos, destPos);
        }
        int width = Tib.size(srcTib);
        long from = Magic.addressOf(src) + Tib.ARRAY_DATA + (long) srcPos * width;
        long to = Magic.addressOf(dest) + Tib.ARRAY_DATA + (long) destPos * width;
        if (!srcRefs || Tib.isAssignable(srcTib, destTib)) {
            Magic.copyMemory(to, from, (long) length * width);
            return;
        }
        long element = Tib.element(destTib);
        for (int i = 0; i < length; i++) {
            long ref = Magic.peekLong(from + 8L * i);
            if (ref != 0 && !Tib.isAssignable(Magic.peekLong(ref), element)) {
                Panic.panic("ArrayStoreException: arraycopy: element type mismatch");
            }
            Magic.pokeLong(to + 8L * i, ref);
        }
    }

    public static int identityHashCode(Object object) {
        return object == null ? 0 : Heap.identityHash(object);
    }
}
