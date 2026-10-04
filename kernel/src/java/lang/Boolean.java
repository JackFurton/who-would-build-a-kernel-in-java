package java.lang;

public final class Boolean implements Comparable<Boolean> {

    public static final Boolean TRUE = new Boolean(true);
    public static final Boolean FALSE = new Boolean(false);

    private final boolean value;

    private Boolean(boolean value) {
        this.value = value;
    }

    public static Boolean valueOf(boolean b) {
        return b ? TRUE : FALSE;
    }

    public static boolean parseBoolean(String s) {
        return "true".equals(s) || s != null && s.length() == 4 && (s.charAt(0) | 0x20) == 't'
                && (s.charAt(1) | 0x20) == 'r' && (s.charAt(2) | 0x20) == 'u' && (s.charAt(3) | 0x20) == 'e';
    }

    public static String toString(boolean b) {
        return b ? "true" : "false";
    }

    public static int hashCode(boolean b) {
        return b ? 1231 : 1237;
    }

    public static int compare(boolean x, boolean y) {
        return x == y ? 0 : (x ? 1 : -1);
    }

    public boolean booleanValue() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Boolean b && b.value == value;
    }

    @Override
    public int hashCode() {
        return hashCode(value);
    }

    @Override
    public String toString() {
        return toString(value);
    }

    @Override
    public int compareTo(Boolean other) {
        return compare(value, other.value);
    }
}
