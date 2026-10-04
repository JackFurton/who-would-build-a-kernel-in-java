package java.util;

import java.util.function.Predicate;

public class ArrayList<E> extends AbstractList<E> {

    private Object[] elementData;
    private int size;

    public ArrayList() {
        this(10);
    }

    public ArrayList(int initialCapacity) {
        if (initialCapacity < 0) {
            throw new IllegalArgumentException("Illegal Capacity: " + initialCapacity);
        }
        elementData = new Object[initialCapacity];
    }

    public ArrayList(Collection<? extends E> c) {
        elementData = c.toArray();
        size = elementData.length;
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    @SuppressWarnings("unchecked")
    public E get(int index) {
        Objects.checkIndex(index, size);
        return (E) elementData[index];
    }

    @Override
    @SuppressWarnings("unchecked")
    public E set(int index, E element) {
        Objects.checkIndex(index, size);
        E old = (E) elementData[index];
        elementData[index] = element;
        return old;
    }

    @Override
    public boolean add(E e) {
        modCount++;
        grow(size + 1);
        elementData[size++] = e;
        return true;
    }

    @Override
    public void add(int index, E element) {
        if (index > size || index < 0) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
        }
        modCount++;
        grow(size + 1);
        System.arraycopy(elementData, index, elementData, index + 1, size - index);
        elementData[index] = element;
        size++;
    }

    @Override
    @SuppressWarnings("unchecked")
    public E remove(int index) {
        Objects.checkIndex(index, size);
        modCount++;
        E old = (E) elementData[index];
        System.arraycopy(elementData, index + 1, elementData, index, size - index - 1);
        elementData[--size] = null;
        return old;
    }

    @Override
    public boolean remove(Object o) {
        int i = indexOf(o);
        if (i < 0) {
            return false;
        }
        remove(i);
        return true;
    }

    @Override
    public void clear() {
        modCount++;
        for (int i = 0; i < size; i++) {
            elementData[i] = null;
        }
        size = 0;
    }

    @Override
    public boolean addAll(Collection<? extends E> c) {
        Object[] a = c.toArray();
        modCount++;
        grow(size + a.length);
        System.arraycopy(a, 0, elementData, size, a.length);
        size += a.length;
        return a.length != 0;
    }

    @Override
    public boolean removeIf(Predicate<? super E> filter) {
        int expected = modCount;
        int kept = 0;
        for (int i = 0; i < size; i++) {
            @SuppressWarnings("unchecked")
            E e = (E) elementData[i];
            if (!filter.test(e)) {
                elementData[kept++] = e;
            }
        }
        if (modCount != expected) {
            throw new ConcurrentModificationException();
        }
        boolean removed = kept != size;
        for (int i = kept; i < size; i++) {
            elementData[i] = null;
        }
        if (removed) {
            modCount++;
        }
        size = kept;
        return removed;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void sort(Comparator<? super E> c) {
        int expected = modCount;
        Object[] a = new Object[size];
        System.arraycopy(elementData, 0, a, 0, size);
        Arrays.sort(a, (Comparator) c);
        System.arraycopy(a, 0, elementData, 0, size);
        if (modCount != expected) {
            throw new ConcurrentModificationException();
        }
        modCount++;
    }

    @Override
    public Object[] toArray() {
        Object[] a = new Object[size];
        System.arraycopy(elementData, 0, a, 0, size);
        return a;
    }

    public void ensureCapacity(int minCapacity) {
        grow(minCapacity);
    }

    private void grow(int minCapacity) {
        if (minCapacity > elementData.length) {
            Object[] bigger = new Object[Math.max(minCapacity, elementData.length + (elementData.length >> 1))];
            System.arraycopy(elementData, 0, bigger, 0, size);
            elementData = bigger;
        }
    }
}
