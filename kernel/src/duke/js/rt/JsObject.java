package duke.js.rt;

/** A plain object: string keys in insertion order. Lookup is linear, which suits small objects. */
public final class JsObject {

    private String[] keys = new String[4];
    private Object[] values = new Object[4];
    private int size;

    private int find(String key) {
        for (int i = 0; i < size; i++) {
            if (keys[i].equals(key)) {
                return i;
            }
        }
        return -1;
    }

    public Object get(String key) {
        int i = find(key);
        return i < 0 ? null : values[i];
    }

    public boolean has(String key) {
        return find(key) >= 0;
    }

    public void set(String key, Object value) {
        int i = find(key);
        if (i >= 0) {
            values[i] = value;
            return;
        }
        if (size == keys.length) {
            String[] newKeys = new String[size * 2];
            Object[] newValues = new Object[size * 2];
            for (int j = 0; j < size; j++) {
                newKeys[j] = keys[j];
                newValues[j] = values[j];
            }
            keys = newKeys;
            values = newValues;
        }
        keys[size] = key;
        values[size] = value;
        size++;
    }

    public int size() {
        return size;
    }

    public String keyAt(int i) {
        return keys[i];
    }

    public Object valueAt(int i) {
        return values[i];
    }
}
