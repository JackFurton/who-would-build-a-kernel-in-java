package duke.conformance;

final class Conversions {

    static long intToLong() { return (long) opaque(-5); }
    static int longToInt() { return (int) opaque(0x1_2345_6789L); }
    static int intToByte() { return (byte) opaque(200); }
    static int intToChar() { return (char) opaque(-1); }
    static int intToShort() { return (short) opaque(40000); }
    static long widenInMultiply() { return opaque(100000) * (long) opaque(100000); }
    static long overflowBeforeWiden() { return (long) (opaque(100000) * opaque(100000)); }

    static int opaque(int v) { return v; }

    static long opaque(long v) { return v; }
}
