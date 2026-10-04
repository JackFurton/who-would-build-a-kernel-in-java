package java.util;

import java.util.function.Function;
import java.util.function.ToIntFunction;
import java.util.function.ToLongFunction;

@FunctionalInterface
public interface Comparator<T> {

    int compare(T a, T b);

    default Comparator<T> reversed() {
        return (a, b) -> compare(b, a);
    }

    default Comparator<T> thenComparing(Comparator<? super T> other) {
        return (a, b) -> {
            int c = compare(a, b);
            return c != 0 ? c : other.compare(a, b);
        };
    }

    default <U extends Comparable<? super U>> Comparator<T> thenComparing(Function<? super T, ? extends U> key) {
        return thenComparing(comparing(key));
    }

    static <T, U extends Comparable<? super U>> Comparator<T> comparing(Function<? super T, ? extends U> key) {
        return (a, b) -> key.apply(a).compareTo(key.apply(b));
    }

    static <T> Comparator<T> comparingInt(ToIntFunction<? super T> key) {
        return (a, b) -> Integer.compare(key.applyAsInt(a), key.applyAsInt(b));
    }

    static <T> Comparator<T> comparingLong(ToLongFunction<? super T> key) {
        return (a, b) -> Long.compare(key.applyAsLong(a), key.applyAsLong(b));
    }

    @SuppressWarnings("unchecked")
    static <T extends Comparable<? super T>> Comparator<T> naturalOrder() {
        return (a, b) -> a.compareTo(b);
    }

    static <T extends Comparable<? super T>> Comparator<T> reverseOrder() {
        return (a, b) -> b.compareTo(a);
    }
}
