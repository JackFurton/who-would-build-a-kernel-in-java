package java.util;

import java.util.function.Predicate;

public interface Collection<E> extends Iterable<E> {

    int size();

    boolean isEmpty();

    boolean contains(Object o);

    boolean add(E e);

    boolean remove(Object o);

    void clear();

    Object[] toArray();

    default boolean addAll(Collection<? extends E> other) {
        boolean changed = false;
        for (E e : other) {
            changed |= add(e);
        }
        return changed;
    }

    default boolean containsAll(Collection<?> other) {
        for (Object o : other) {
            if (!contains(o)) {
                return false;
            }
        }
        return true;
    }

    default boolean removeIf(Predicate<? super E> filter) {
        boolean removed = false;
        for (Iterator<E> it = iterator(); it.hasNext(); ) {
            if (filter.test(it.next())) {
                it.remove();
                removed = true;
            }
        }
        return removed;
    }

    default boolean removeAll(Collection<?> other) {
        return removeIf(other::contains);
    }

    default boolean retainAll(Collection<?> other) {
        return removeIf(e -> !other.contains(e));
    }
}
