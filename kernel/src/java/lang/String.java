package java.lang;

/**
 * Latin-1 only for now. Literals are laid out by the compiler, so {@code value} being the only
 * reference field is part of its contract with this class.
 */
public final class String {

    private final byte[] value;

    String(byte[] value) {
        this.value = value;
    }

    public int length() {
        return value.length;
    }

    public boolean isEmpty() {
        return value.length == 0;
    }

    public char charAt(int index) {
        return (char) (value[index] & 0xFF);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof String s) || s.value.length != value.length) {
            return false;
        }
        for (int i = 0; i < value.length; i++) {
            if (value[i] != s.value[i]) {
                return false;
            }
        }
        return true;
    }

    /** Same algorithm as the JDK, so hashes agree with HotSpot for Latin-1 strings. */
    @Override
    public int hashCode() {
        int h = 0;
        for (byte b : value) {
            h = 31 * h + (b & 0xFF);
        }
        return h;
    }

    @Override
    public String toString() {
        return this;
    }

    public int compareTo(String other) {
        int n = Math.min(value.length, other.value.length);
        for (int i = 0; i < n; i++) {
            int diff = (value[i] & 0xFF) - (other.value[i] & 0xFF);
            if (diff != 0) {
                return diff;
            }
        }
        return value.length - other.value.length;
    }

    public int indexOf(int ch) {
        return indexOf(ch, 0);
    }

    public int indexOf(int ch, int fromIndex) {
        for (int i = Math.max(fromIndex, 0); i < value.length; i++) {
            if ((value[i] & 0xFF) == ch) {
                return i;
            }
        }
        return -1;
    }

    public int lastIndexOf(int ch) {
        for (int i = value.length - 1; i >= 0; i--) {
            if ((value[i] & 0xFF) == ch) {
                return i;
            }
        }
        return -1;
    }

    public boolean startsWith(String prefix) {
        return regionMatches(0, prefix);
    }

    public boolean endsWith(String suffix) {
        return regionMatches(value.length - suffix.value.length, suffix);
    }

    private boolean regionMatches(int offset, String other) {
        if (offset < 0 || offset + other.value.length > value.length) {
            return false;
        }
        for (int i = 0; i < other.value.length; i++) {
            if (value[offset + i] != other.value[i]) {
                return false;
            }
        }
        return true;
    }

    public String substring(int beginIndex) {
        return substring(beginIndex, value.length);
    }

    public String substring(int beginIndex, int endIndex) {
        if (beginIndex < 0 || endIndex > value.length || beginIndex > endIndex) {
            duke.kernel.Panic.panic("StringIndexOutOfBoundsException", beginIndex, endIndex);
        }
        if (beginIndex == 0 && endIndex == value.length) {
            return this;
        }
        byte[] copy = new byte[endIndex - beginIndex];
        System.arraycopy(value, beginIndex, copy, 0, copy.length);
        return new String(copy);
    }

    public String concat(String other) {
        if (other.value.length == 0) {
            return this;
        }
        byte[] joined = new byte[value.length + other.value.length];
        System.arraycopy(value, 0, joined, 0, value.length);
        System.arraycopy(other.value, 0, joined, value.length, other.value.length);
        return new String(joined);
    }

    public static String valueOf(Object object) {
        return object == null ? "null" : object.toString();
    }

    public static String valueOf(int i) {
        return Integer.toString(i);
    }

    public static String valueOf(long l) {
        return Long.toString(l);
    }

    public static String valueOf(boolean b) {
        return b ? "true" : "false";
    }

    public static String valueOf(char c) {
        return new String(new byte[] {(byte) c});
    }
}
