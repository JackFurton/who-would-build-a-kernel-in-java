package java.util;

public final class Collections {

    private static final List<Object> EMPTY_LIST = new ArrayList<>(0);

    private Collections() {
    }

    @SuppressWarnings("unchecked")
    public static <T> List<T> emptyList() {
        return (List<T>) unmodifiableList(EMPTY_LIST);
    }

    public static <K, V> Map<K, V> emptyMap() {
        return new HashMap<>(0);
    }

    public static <T> Set<T> emptySet() {
        return new HashSet<>(0);
    }

    public static <T> List<T> unmodifiableList(List<? extends T> list) {
        return new AbstractList<>() {
            @Override
            public T get(int index) {
                return list.get(index);
            }

            @Override
            public int size() {
                return list.size();
            }
        };
    }

    public static <T extends Comparable<? super T>> void sort(List<T> list) {
        list.sort(null);
    }

    public static <T> void sort(List<T> list, Comparator<? super T> c) {
        list.sort(c);
    }

    public static void reverse(List<?> list) {
        swapAll(list);
    }

    private static <T> void swapAll(List<T> list) {
        for (int i = 0, j = list.size() - 1; i < j; i++, j--) {
            T t = list.get(i);
            list.set(i, list.get(j));
            list.set(j, t);
        }
    }

    public static <T> void swap(List<T> list, int i, int j) {
        T t = list.get(i);
        list.set(i, list.get(j));
        list.set(j, t);
    }
}
