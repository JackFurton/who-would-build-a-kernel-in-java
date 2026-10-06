package duke.js.rt;

/** A JavaScript array. Holes are not modelled: growing past the end fills with undefined. */
public final class JsArray {

    private Object[] items;
    private int length;
    private JsObject named;
    private boolean frozen;
    private boolean sealed;

    public JsArray() {
        items = new Object[4];
    }

    public JsArray(Object[] initial) {
        items = new Object[Math.max(4, initial.length)];
        for (int i = 0; i < initial.length; i++) {
            items[i] = initial[i];
        }
        length = initial.length;
    }

    /** Properties other than indexes and {@code length}, such as the {@code raw} of a template's strings; null if none. */
    JsObject named() {
        return named;
    }

    JsObject namedOrCreate() {
        if (named == null) {
            named = new JsObject();
        }
        return named;
    }

    /** Makes the array read-only (frozen) or fixed in length (sealed). */
    void lock(boolean readonly) {
        sealed = true;
        frozen = frozen || readonly;
    }

    boolean isFrozen() {
        return frozen;
    }

    boolean isSealed() {
        return sealed;
    }

    private void checkWritable() {
        if (frozen) {
            throw new JsError("TypeError: Cannot assign to read only property of object '[object Array]'");
        }
    }

    private void checkExtensible() {
        if (sealed) {
            throw new JsError("TypeError: Cannot add property " + length + ", object is not extensible");
        }
    }

    public int length() {
        return length;
    }

    public Object get(int i) {
        return i >= 0 && i < length ? items[i] : null;
    }

    public void set(int i, Object value) {
        checkWritable();
        while (length <= i) {
            add(null);
        }
        items[i] = value;
    }

    public void add(Object value) {
        checkExtensible();
        if (length == items.length) {
            Object[] bigger = new Object[length * 2];
            for (int i = 0; i < length; i++) {
                bigger[i] = items[i];
            }
            items = bigger;
        }
        items[length++] = value;
    }

    public Object removeLast() {
        if (sealed && length > 0) {
            throw new JsError("TypeError: Cannot delete property '" + (length - 1) + "' of [object Array]");
        }
        if (length == 0) {
            return null;
        }
        Object value = items[--length];
        items[length] = null;
        return value;
    }

    public Object removeFirst() {
        if (sealed && length > 0) {
            throw new JsError("TypeError: Cannot delete property '" + (length - 1) + "' of [object Array]");
        }
        if (length == 0) {
            return null;
        }
        Object value = items[0];
        for (int i = 1; i < length; i++) {
            items[i - 1] = items[i];
        }
        items[--length] = null;
        return value;
    }

    public void addFirst(Object value) {
        add(null);
        for (int i = length - 1; i > 0; i--) {
            items[i] = items[i - 1];
        }
        items[0] = value;
    }

    /** Sets the length, truncating or padding with undefined. */
    public void setLength(int n) {
        if (n != length) {
            checkWritable();
        }
        while (length < n) {
            add(null);
        }
        while (length > n) {
            items[--length] = null;
        }
    }
}
