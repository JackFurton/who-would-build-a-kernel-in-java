package java.util;

public abstract class AbstractSet<E> extends AbstractCollection<E> implements Set<E> {

    protected AbstractSet() {
    }

    @Override
    public boolean equals(Object o) {
        if (o == this) {
            return true;
        }
        if (!(o instanceof Set<?> other) || other.size() != size()) {
            return false;
        }
        return containsAll(other);
    }

    @Override
    public int hashCode() {
        int h = 0;
        for (E e : this) {
            h += e == null ? 0 : e.hashCode();
        }
        return h;
    }
}
