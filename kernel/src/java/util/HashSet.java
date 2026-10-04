package java.util;

public class HashSet<E> extends AbstractSet<E> {

    private static final Object PRESENT = new Object();

    private final HashMap<E, Object> map;

    public HashSet() {
        map = new HashMap<>();
    }

    public HashSet(int initialCapacity) {
        map = new HashMap<>(initialCapacity);
    }

    /** The JDK sizes this as max(size / .75f + 1, 16); in integers, size * 4 / 3 + 1. */
    public HashSet(Collection<? extends E> c) {
        map = new HashMap<>(Math.max(c.size() * 4 / 3 + 1, 16));
        addAll(c);
    }

    @Override
    public Iterator<E> iterator() {
        return map.keySet().iterator();
    }

    @Override
    public int size() {
        return map.size();
    }

    @Override
    public boolean contains(Object o) {
        return map.containsKey(o);
    }

    @Override
    public boolean add(E e) {
        return map.put(e, PRESENT) == null;
    }

    @Override
    public boolean remove(Object o) {
        return map.remove(o) == PRESENT;
    }

    @Override
    public void clear() {
        map.clear();
    }
}
