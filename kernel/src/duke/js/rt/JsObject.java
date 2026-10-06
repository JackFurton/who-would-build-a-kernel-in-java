package duke.js.rt;

/**
 * A plain object: string keys in insertion order, an optional prototype, and per-property attributes
 * (enumerable, writable, configurable) or a getter and setter. Lookup is linear, which suits small objects.
 */
public final class JsObject {

    /** A property with a getter and a setter, stored in place of a value. */
    static final class Accessor {
        JsFunction getter;
        JsFunction setter;

        Accessor(JsFunction getter, JsFunction setter) {
            this.getter = getter;
            this.setter = setter;
        }
    }

    private static final int HIDDEN = 1;
    private static final int READONLY = 2;
    private static final int FIXED = 4;

    /** The prototype, or null for none. */
    public JsObject proto;

    private String[] keys = new String[4];
    private Object[] values = new Object[4];
    private byte[] flags = new byte[4];
    private int size;
    private boolean extensible = true;

    private int find(String key) {
        for (int i = 0; i < size; i++) {
            if (keys[i].equals(key)) {
                return i;
            }
        }
        return -1;
    }

    /** The value of {@code key} on this object or its prototype chain, running a getter with this object as {@code this}. */
    public Object get(String key) {
        for (JsObject o = this; o != null; o = o.proto) {
            int i = o.find(key);
            if (i >= 0) {
                return resolve(o.values[i]);
            }
        }
        return null;
    }

    /** Like {@link #get}, but a getter runs with {@code receiver} as {@code this}: for {@code super.x}. */
    Object getFor(String key, Object receiver) {
        for (JsObject o = this; o != null; o = o.proto) {
            int i = o.find(key);
            if (i >= 0) {
                Object stored = o.values[i];
                if (stored instanceof Accessor) {
                    Accessor a = (Accessor) stored;
                    return a.getter == null ? null : a.getter.call(receiver, new Object[0]);
                }
                return stored;
            }
        }
        return null;
    }

    public Object getOwn(String key) {
        int i = find(key);
        return i < 0 ? null : resolve(values[i]);
    }

    private Object resolve(Object stored) {
        if (stored instanceof Accessor) {
            Accessor a = (Accessor) stored;
            return a.getter == null ? null : a.getter.call(this, new Object[0]);
        }
        return stored;
    }

    /** The stored value or accessor of an own property, unresolved. */
    Object getOwnRaw(String key) {
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

    /**
     * Sets an own data property without checking attributes or running setters: for the runtime's own use.
     * A new property is enumerable unless its name starts with '#', which marks a private field.
     */
    public void set(String key, Object value) {
        int i = find(key);
        if (i >= 0) {
            values[i] = value;
            return;
        }
        append(key, value, key.startsWith("#") ? HIDDEN : 0);
    }

    /** Sets an own property that enumeration skips, like the methods on a built-in prototype. */
    public void setHidden(String key, Object value) {
        int i = find(key);
        if (i >= 0) {
            values[i] = value;
            flags[i] = (byte) (flags[i] | HIDDEN);
            return;
        }
        append(key, value, HIDDEN);
    }

    private void append(String key, Object value, int attributes) {
        if (size == keys.length) {
            String[] newKeys = new String[size * 2];
            Object[] newValues = new Object[size * 2];
            byte[] newFlags = new byte[size * 2];
            for (int j = 0; j < size; j++) {
                newKeys[j] = keys[j];
                newValues[j] = values[j];
                newFlags[j] = flags[j];
            }
            keys = newKeys;
            values = newValues;
            flags = newFlags;
        }
        keys[size] = key;
        values[size] = value;
        flags[size] = (byte) attributes;
        size++;
    }

    /**
     * {@code this[key] = value} as JavaScript does it in strict code: a setter runs if one is found on the chain, a
     * read-only property or a frozen object throws, and otherwise the property is created or updated here.
     */
    public void assign(String key, Object value) {
        for (JsObject o = this; o != null; o = o.proto) {
            int i = o.find(key);
            if (i < 0) {
                continue;
            }
            Object stored = o.values[i];
            if (stored instanceof Accessor) {
                Accessor a = (Accessor) stored;
                if (a.setter == null) {
                    throw new JsError("TypeError: Cannot set property " + key + " of #<Object> which has only a getter");
                }
                a.setter.call(this, new Object[] {value});
                return;
            }
            if ((o.flags[i] & READONLY) != 0) {
                throw new JsError("TypeError: Cannot assign to read only property '" + key + "' of object");
            }
            if (o == this) {
                values[i] = value;
                return;
            }
            break;
        }
        if (!extensible) {
            throw new JsError("TypeError: Cannot add property " + key + ", object is not extensible");
        }
        set(key, value);
    }

    /** Defines or redefines an own property: a data property if {@code value} isn't an Accessor. */
    void define(String key, Object value, boolean enumerable, boolean writable, boolean configurable) {
        int attributes = (enumerable ? 0 : HIDDEN) | (writable || value instanceof Accessor ? 0 : READONLY)
                | (configurable ? 0 : FIXED);
        int i = find(key);
        if (i >= 0) {
            values[i] = value;
            flags[i] = (byte) attributes;
        } else {
            append(key, value, attributes);
        }
    }

    void defineAccessor(String key, JsFunction getter, JsFunction setter) {
        defineAccessor(key, getter, setter, true);
    }

    /** Adds a getter or setter, joining an accessor already there; a class's are not enumerable. */
    void defineAccessor(String key, JsFunction getter, JsFunction setter, boolean enumerable) {
        int i = find(key);
        if (i >= 0 && values[i] instanceof Accessor) {
            Accessor a = (Accessor) values[i];
            a.getter = getter != null ? getter : a.getter;
            a.setter = setter != null ? setter : a.setter;
            return;
        }
        define(key, new Accessor(getter, setter), enumerable, true, true);
    }

    /** Removes an own property; false if it can't be removed (non-configurable) or there was none. */
    public boolean remove(String key) {
        int i = find(key);
        if (i < 0 || (flags[i] & FIXED) != 0) {
            return false;
        }
        for (int j = i + 1; j < size; j++) {
            keys[j - 1] = keys[j];
            values[j - 1] = values[j];
            flags[j - 1] = flags[j];
        }
        size--;
        keys[size] = null;
        values[size] = null;
        return true;
    }

    public boolean isEnumerable(String key) {
        int i = find(key);
        return i >= 0 && (flags[i] & HIDDEN) == 0;
    }

    boolean isWritable(String key) {
        int i = find(key);
        return i >= 0 && (flags[i] & READONLY) == 0;
    }

    boolean isConfigurable(String key) {
        int i = find(key);
        return i >= 0 && (flags[i] & FIXED) == 0;
    }

    boolean isExtensible() {
        return extensible;
    }

    void preventExtensions() {
        extensible = false;
    }

    /** Makes every property non-configurable, and with {@code readonly} also read-only (data properties). */
    void lock(boolean readonly) {
        extensible = false;
        for (int i = 0; i < size; i++) {
            flags[i] = (byte) (flags[i] | FIXED | (readonly && !(values[i] instanceof Accessor) ? READONLY : 0));
        }
    }

    /** Frozen (readonly true) or sealed (readonly false): not extensible, nothing configurable, and for frozen nothing writable. */
    boolean isLocked(boolean readonly) {
        if (extensible) {
            return false;
        }
        for (int i = 0; i < size; i++) {
            if ((flags[i] & FIXED) == 0 || readonly && (flags[i] & READONLY) == 0 && !(values[i] instanceof Accessor)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Own enumerable keys in the order JavaScript enumerates them: array indexes ascending, then the rest
     * in the order they were added.
     */
    public String[] keys() {
        return orderedKeys(false);
    }

    /** Every own key, enumerable or not. */
    String[] allKeys() {
        return orderedKeys(true);
    }

    private String[] orderedKeys(boolean includeHidden) {
        int count = 0;
        for (int i = 0; i < size; i++) {
            if (counted(i, includeHidden)) {
                count++;
            }
        }
        String[] out = new String[count];
        int n = 0;
        for (int i = 0; i < size; i++) {
            if (counted(i, includeHidden) && isIndex(keys[i])) {
                int at = n++;
                while (at > 0 && Long.parseLong(out[at - 1]) > Long.parseLong(keys[i])) {
                    out[at] = out[at - 1];
                    at--;
                }
                out[at] = keys[i];
            }
        }
        for (int i = 0; i < size; i++) {
            if (counted(i, includeHidden) && !isIndex(keys[i])) {
                out[n++] = keys[i];
            }
        }
        return out;
    }

    /** Whether key {@code i} is reported: enumerable ones, plus the others when asked, but never a private '#' name. */
    private boolean counted(int i, boolean includeHidden) {
        return (includeHidden || (flags[i] & HIDDEN) == 0) && !keys[i].startsWith("#");
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
