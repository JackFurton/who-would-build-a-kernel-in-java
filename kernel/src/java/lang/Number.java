package java.lang;

/** Without floatValue/doubleValue until floating point lands (#26). */
public abstract class Number {

    public abstract int intValue();

    public abstract long longValue();

    public byte byteValue() {
        return (byte) intValue();
    }

    public short shortValue() {
        return (short) intValue();
    }
}
