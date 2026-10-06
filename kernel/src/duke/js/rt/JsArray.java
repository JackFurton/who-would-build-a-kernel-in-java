package duke.js.rt;

/** A JavaScript array. Holes are not modelled: growing past the end fills with undefined. */
public final class JsArray {

    private Object[] items;
    private int length;

    public JsArray() {
        items = new Object[4];
    }

    public JsArray(Object[] initial) {
        items = new Object[Math.max(4, initial.length)];
        System.arraycopy(initial, 0, items, 0, initial.length);
        length = initial.length;
    }

    public int length() {
        return length;
    }

    public Object get(int i) {
        return i >= 0 && i < length ? items[i] : null;
    }

    public void set(int i, Object value) {
        while (length <= i) {
            add(null);
        }
        items[i] = value;
    }

    public void add(Object value) {
        if (length == items.length) {
            Object[] bigger = new Object[length * 2];
            System.arraycopy(items, 0, bigger, 0, length);
            items = bigger;
        }
        items[length++] = value;
    }

    public Object removeLast() {
        if (length == 0) {
            return null;
        }
        Object value = items[--length];
        items[length] = null;
        return value;
    }

    public Object removeFirst() {
        if (length == 0) {
            return null;
        }
        Object value = items[0];
        System.arraycopy(items, 1, items, 0, length - 1);
        items[--length] = null;
        return value;
    }

    public void addFirst(Object value) {
        add(null);
        System.arraycopy(items, 0, items, 1, length - 1);
        items[0] = value;
    }

    /** Sets the length, truncating or padding with undefined. */
    public void setLength(int n) {
        while (length < n) {
            add(null);
        }
        while (length > n) {
            items[--length] = null;
        }
    }
}
