package duke.js.rt;

import java.util.HashMap;

/**
 * Map, Set, WeakMap and WeakSet: entries in insertion order, found through a hash index. Deleted entries leave a gap
 * so a loop in progress keeps its place; the gaps are squeezed out when nothing is iterating.
 *
 * <p>Keys compare as JavaScript's SameValueZero: numbers and strings by value, objects by identity. The weak variants
 * are not weak here: they hold their keys, which only matters for how long garbage lives.
 */
public final class JsMap {

    /** What a deleted entry's key becomes. */
    private static final Object GONE = new Object();
    /** undefined can be a key, but Java null can't be a hash key. */
    private static final Object UNDEFINED = new Object();

    final boolean isSet;
    final boolean weak;

    private Object[] keys = new Object[8];
    private Object[] values = new Object[8];
    private int used;
    private int live;
    /** Loops and iterators currently positioned in this collection. */
    private int iterating;
    private final HashMap<Object, Integer> index = new HashMap<>();
    private JsObject named;

    JsMap(boolean isSet, boolean weak) {
        this.isSet = isSet;
        this.weak = weak;
    }

    private static Object normalize(Object key) {
        return key == null ? UNDEFINED : key;
    }

    int size() {
        return live;
    }

    boolean has(Object key) {
        return index.containsKey(normalize(key));
    }

    Object get(Object key) {
        Integer i = index.get(normalize(key));
        return i == null ? null : values[i.intValue()];
    }

    void put(Object key, Object value) {
        Object k = normalize(key);
        Integer i = index.get(k);
        if (i != null) {
            values[i.intValue()] = value;
            return;
        }
        if (iterating == 0 && used >= 64 && live < used / 2) {
            compact();
        }
        if (used == keys.length) {
            Object[] newKeys = new Object[used * 2];
            Object[] newValues = new Object[used * 2];
            for (int j = 0; j < used; j++) {
                newKeys[j] = keys[j];
                newValues[j] = values[j];
            }
            keys = newKeys;
            values = newValues;
        }
        keys[used] = k;
        values[used] = value;
        index.put(k, Integer.valueOf(used));
        used++;
        live++;
    }

    boolean delete(Object key) {
        Object k = normalize(key);
        Integer i = index.remove(k);
        if (i == null) {
            return false;
        }
        keys[i.intValue()] = GONE;
        values[i.intValue()] = null;
        live--;
        return true;
    }

    void clear() {
        for (int i = 0; i < used; i++) {
            keys[i] = GONE;
            values[i] = null;
        }
        index.clear();
        live = 0;
        if (iterating == 0) {
            used = 0;
        }
    }

    private void compact() {
        int to = 0;
        for (int from = 0; from < used; from++) {
            if (keys[from] != GONE) {
                keys[to] = keys[from];
                values[to] = values[from];
                index.put(keys[to], Integer.valueOf(to));
                to++;
            }
        }
        for (int i = to; i < used; i++) {
            keys[i] = null;
            values[i] = null;
        }
        used = to;
    }

    /** The key at entry position {@code i}, or GONE-like null if that entry was deleted; positions run up to {@link #limit}. */
    boolean liveAt(int i) {
        return keys[i] != GONE;
    }

    Object keyAt(int i) {
        return keys[i] == UNDEFINED ? null : keys[i];
    }

    Object valueAt(int i) {
        return values[i];
    }

    int limit() {
        return used;
    }

    void beginIteration() {
        iterating++;
    }

    void endIteration() {
        iterating--;
    }

    JsObject named() {
        return named;
    }

    JsObject namedOrCreate() {
        if (named == null) {
            named = new JsObject();
        }
        return named;
    }

    /** Walks the entries, skipping deleted ones and seeing ones added during the walk. */
    JsIter iterate(int kind) {
        beginIteration();
        return new JsIter() {
            private int position = -1;
            private boolean finished;

            @Override
            public boolean next() {
                if (finished) {
                    return false;
                }
                do {
                    position++;
                } while (position < used && !liveAt(position));
                if (position >= used) {
                    finished = true;
                    endIteration();
                    return false;
                }
                return true;
            }

            @Override
            public Object value() {
                Object key = keyAt(position);
                Object value = isSet ? key : valueAt(position);
                if (kind == 0) {
                    return key;
                }
                return kind == 1 ? value : new JsArray(new Object[] {key, value});
            }

            @Override
            public void close() {
                if (!finished) {
                    finished = true;
                    endIteration();
                }
            }
        };
    }
}
