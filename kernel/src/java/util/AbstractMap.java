package java.util;

public abstract class AbstractMap<K, V> implements Map<K, V> {

    protected AbstractMap() {
    }

    @Override
    public boolean isEmpty() {
        return size() == 0;
    }

    @Override
    public boolean containsValue(Object value) {
        for (Entry<K, V> e : entrySet()) {
            if (Objects.equals(value, e.getValue())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean equals(Object o) {
        if (o == this) {
            return true;
        }
        if (!(o instanceof Map<?, ?> other) || other.size() != size()) {
            return false;
        }
        for (Entry<K, V> e : entrySet()) {
            Object theirs = other.get(e.getKey());
            if (!Objects.equals(e.getValue(), theirs) || theirs == null && !other.containsKey(e.getKey())) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        int h = 0;
        for (Entry<K, V> e : entrySet()) {
            h += e.hashCode();
        }
        return h;
    }

    @Override
    public String toString() {
        Iterator<Entry<K, V>> it = entrySet().iterator();
        if (!it.hasNext()) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder("{");
        while (true) {
            Entry<K, V> e = it.next();
            K key = e.getKey();
            V value = e.getValue();
            sb.append(key == this ? "(this Map)" : key).append('=').append(value == this ? "(this Map)" : value);
            if (!it.hasNext()) {
                return sb.append('}').toString();
            }
            sb.append(", ");
        }
    }
}
