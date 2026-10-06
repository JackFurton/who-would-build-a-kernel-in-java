package duke.js.rt;

/**
 * A plain object: string keys, an optional prototype, and a flag per property saying whether
 * enumeration sees it. Lookup is linear, which suits small objects.
 */
public final class JsObject {

    /** The prototype, or null for none. */
    public JsObject proto;

    private String[] keys = new String[4];
    private Object[] values = new Object[4];
    private boolean[] hidden = new boolean[4];
    private int size;

    private int find(String key) {
        for (int i = 0; i < size; i++) {
            if (keys[i].equals(key)) {
                return i;
            }
        }
        return -1;
    }

    /** The value of {@code key} on this object or its prototype chain; undefined if absent. */
    public Object get(String key) {
        for (JsObject o = this; o != null; o = o.proto) {
            int i = o.find(key);
            if (i >= 0) {
                return o.values[i];
            }
        }
        return null;
    }

    public Object getOwn(String key) {
        int i = find(key);
        return i < 0 ? null : values[i];
    }

    public boolean hasOwn(String key) {
        return find(key) >= 0;
    }

    /** True if {@code key} is on this object or its prototype chain. */
    public boolean has(String key) {
        for (JsObject o = this; o != null; o = o.proto) {
            if (o.find(key) >= 0) {
                return true;
            }
        }
        return false;
    }

    /** Sets an own property, keeping its enumerability if it exists and making it enumerable if it is new. */
    public void set(String key, Object value) {
        put(key, value, false, false);
    }

    /** Sets an own property that enumeration skips, like the methods on a built-in prototype. */
    public void setHidden(String key, Object value) {
        put(key, value, true, true);
    }

    private void put(String key, Object value, boolean isHidden, boolean overrideFlag) {
        int i = find(key);
        if (i >= 0) {
            values[i] = value;
            if (overrideFlag) {
                hidden[i] = isHidden;
            }
            return;
        }
        if (size == keys.length) {
            String[] newKeys = new String[size * 2];
            Object[] newValues = new Object[size * 2];
            boolean[] newHidden = new boolean[size * 2];
            for (int j = 0; j < size; j++) {
                newKeys[j] = keys[j];
                newValues[j] = values[j];
                newHidden[j] = hidden[j];
            }
            keys = newKeys;
            values = newValues;
            hidden = newHidden;
        }
        keys[size] = key;
        values[size] = value;
        hidden[size] = isHidden;
        size++;
    }

    /** Removes an own property; false if there was none. */
    public boolean remove(String key) {
        int i = find(key);
        if (i < 0) {
            return false;
        }
        for (int j = i + 1; j < size; j++) {
            keys[j - 1] = keys[j];
            values[j - 1] = values[j];
            hidden[j - 1] = hidden[j];
        }
        size--;
        keys[size] = null;
        values[size] = null;
        return true;
    }

    public boolean isEnumerable(String key) {
        int i = find(key);
        return i >= 0 && !hidden[i];
    }

    /**
     * Own enumerable keys in the order JavaScript enumerates them: array indexes ascending, then the rest
     * in the order they were added.
     */
    public String[] keys() {
        int count = 0;
        for (int i = 0; i < size; i++) {
            if (!hidden[i]) {
                count++;
            }
        }
        String[] out = new String[count];
        int n = 0;
        for (int i = 0; i < size; i++) {
            if (!hidden[i] && isIndex(keys[i])) {
                int at = n++;
                while (at > 0 && Long.parseLong(out[at - 1]) > Long.parseLong(keys[i])) {
                    out[at] = out[at - 1];
                    at--;
                }
                out[at] = keys[i];
            }
        }
        for (int i = 0; i < size; i++) {
            if (!hidden[i] && !isIndex(keys[i])) {
                out[n++] = keys[i];
            }
        }
        return out;
    }

    /** A canonical array index: "0", or digits with no leading zero, below 2^32 - 1. */
    static boolean isIndex(String s) {
        int n = s.length();
        if (n == 0 || n > 10 || (n > 1 && s.charAt(0) == '0')) {
            return false;
        }
        for (int i = 0; i < n; i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') {
                return false;
            }
        }
        return Long.parseLong(s) < 4294967295L;
    }
}
