package java.util;

public abstract class AbstractCollection<E> implements Collection<E> {

    protected AbstractCollection() {
    }

    @Override
    public boolean isEmpty() {
        return size() == 0;
    }

    @Override
    public boolean contains(Object o) {
        for (E e : this) {
            if (Objects.equals(o, e)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean add(E e) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean remove(Object o) {
        for (Iterator<E> it = iterator(); it.hasNext(); ) {
            if (Objects.equals(o, it.next())) {
                it.remove();
                return true;
            }
        }
        return false;
    }

    @Override
    public void clear() {
        for (Iterator<E> it = iterator(); it.hasNext(); ) {
            it.next();
            it.remove();
        }
    }

    @Override
    public Object[] toArray() {
        Object[] a = new Object[size()];
        int i = 0;
        for (E e : this) {
            a[i++] = e;
        }
        return a;
    }

    @Override
    public String toString() {
        Iterator<E> it = iterator();
        if (!it.hasNext()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        while (true) {
            E e = it.next();
            sb.append(e == this ? "(this Collection)" : e);
            if (!it.hasNext()) {
                return sb.append(']').toString();
            }
            sb.append(", ");
        }
    }
}
