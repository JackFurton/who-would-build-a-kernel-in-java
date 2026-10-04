package java.lang;

/** Latin-1 only for now; literals are laid out by the compiler, so the field layout is part of its contract. */
public final class String {

    private final byte[] value;

    private String(byte[] value) {
        this.value = value;
    }

    public int length() {
        return value.length;
    }

    public char charAt(int index) {
        return (char) (value[index] & 0xFF);
    }
}
