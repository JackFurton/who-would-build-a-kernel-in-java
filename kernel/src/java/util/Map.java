package java.util;

import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;

public interface Map<K, V> {

    interface Entry<K, V> {
        K getKey();

        V getValue();

        V setValue(V value);
    }

    int size();

    boolean isEmpty();

    boolean containsKey(Object key);

    boolean containsValue(Object value);

    V get(Object key);

    V put(K key, V value);

    V remove(Object key);

    void clear();

    Set<K> keySet();

    Collection<V> values();

    Set<Entry<K, V>> entrySet();

    default V getOrDefault(Object key, V defaultValue) {
        V v = get(key);
        return v != null || containsKey(key) ? v : defaultValue;
    }

    default V putIfAbsent(K key, V value) {
        V v = get(key);
        if (v == null) {
            v = put(key, value);
        }
        return v;
    }

    default void putAll(Map<? extends K, ? extends V> other) {
        for (Entry<? extends K, ? extends V> e : other.entrySet()) {
            put(e.getKey(), e.getValue());
        }
    }

    default void forEach(BiConsumer<? super K, ? super V> action) {
        for (Entry<K, V> e : entrySet()) {
            action.accept(e.getKey(), e.getValue());
        }
    }

    default V computeIfAbsent(K key, Function<? super K, ? extends V> mapping) {
        V v = get(key);
        if (v == null) {
            v = mapping.apply(key);
            if (v != null) {
                put(key, v);
            }
        }
        return v;
    }

    default V merge(K key, V value, BiFunction<? super V, ? super V, ? extends V> remapping) {
        V old = get(key);
        V merged = old == null ? value : remapping.apply(old, value);
        if (merged == null) {
            remove(key);
        } else {
            put(key, merged);
        }
        return merged;
    }
}
