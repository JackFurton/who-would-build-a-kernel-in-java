package java.lang;

import duke.kernel.Panic;

/** Latin-1 like String. Also what javac emits for {@code +} on strings (-XDstringConcat=inline). */
public final class StringBuilder {

    private byte[] value;
    private int count;

    public StringBuilder() {
        this(16);
    }

    public StringBuilder(int capacity) {
        value = new byte[capacity];
    }

    public StringBuilder(String initial) {
        this(initial.length() + 16);
        append(initial);
    }

    public StringBuilder append(String s) {
        if (s == null) {
            s = "null";
        }
        int n = s.length();
        ensureCapacity(count + n);
        s.copyBytes(value, count);
        count += n;
        return this;
    }

    public StringBuilder append(Object o) {
        return append(String.valueOf(o));
    }

    public StringBuilder append(char c) {
        if (c > 0xFF) {
            Panic.panic("StringBuilder: non-Latin-1 char", c, count);
        }
        ensureCapacity(count + 1);
        value[count++] = (byte) c;
        return this;
    }

    public StringBuilder append(int i) {
        return append(Integer.toString(i));
    }

    public StringBuilder append(long l) {
        return append(Long.toString(l));
    }

    public StringBuilder append(boolean b) {
        return append(b ? "true" : "false");
    }

    public int length() {
        return count;
    }

    public char charAt(int index) {
        checkIndex(index);
        return (char) (value[index] & 0xFF);
    }

    public void setCharAt(int index, char c) {
        checkIndex(index);
        value[index] = (byte) c;
    }

    public void setLength(int length) {
        if (length < 0) {
            Panic.panic("StringIndexOutOfBoundsException", length, count);
        }
        ensureCapacity(length);
        for (int i = count; i < length; i++) {
            value[i] = 0;
        }
        count = length;
    }

    public StringBuilder reverse() {
        for (int i = 0, j = count - 1; i < j; i++, j--) {
            byte t = value[i];
            value[i] = value[j];
            value[j] = t;
        }
        return this;
    }

    @Override
    public String toString() {
        byte[] bytes = new byte[count];
        System.arraycopy(value, 0, bytes, 0, count);
        return new String(bytes);
    }

    private void checkIndex(int index) {
        if (index < 0 || index >= count) {
            Panic.panic("StringIndexOutOfBoundsException", index, count);
        }
    }

    private void ensureCapacity(int needed) {
        if (needed > value.length) {
            byte[] grown = new byte[Math.max(needed, value.length * 2 + 2)];
            System.arraycopy(value, 0, grown, 0, count);
            value = grown;
        }
    }
}
