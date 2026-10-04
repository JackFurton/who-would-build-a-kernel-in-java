package java.lang;

import duke.kernel.Panic;

/** Static helpers only: no boxing yet (#39). */
public final class Integer {

    public static final int MIN_VALUE = 0x80000000;
    public static final int MAX_VALUE = 0x7fffffff;
    public static final int SIZE = 32;

    private Integer() {
    }

    public static String toString(int i) {
        return Long.toString(i);
    }

    public static String toHexString(int i) {
        return Long.toUnsignedString(i & 0xFFFF_FFFFL, 4);
    }

    public static String toBinaryString(int i) {
        return Long.toUnsignedString(i & 0xFFFF_FFFFL, 1);
    }

    public static int parseInt(String s) {
        return parseInt(s, 10);
    }

    public static int parseInt(String s, int radix) {
        long value = Long.parseLong(s, radix);
        if (value < MIN_VALUE || value > MAX_VALUE) {
            Panic.panic("NumberFormatException: out of int range: ", s);
        }
        return (int) value;
    }

    public static int compare(int x, int y) {
        return x < y ? -1 : (x == y ? 0 : 1);
    }

    public static int signum(int i) {
        return (i >> 31) | (-i >>> 31);
    }

    public static int hashCode(int value) {
        return value;
    }

    public static int min(int a, int b) {
        return Math.min(a, b);
    }

    public static int max(int a, int b) {
        return Math.max(a, b);
    }

    public static int sum(int a, int b) {
        return a + b;
    }

    public static int bitCount(int i) {
        i = i - ((i >>> 1) & 0x55555555);
        i = (i & 0x33333333) + ((i >>> 2) & 0x33333333);
        i = (i + (i >>> 4)) & 0x0f0f0f0f;
        i = i + (i >>> 8);
        i = i + (i >>> 16);
        return i & 0x3f;
    }

    public static int numberOfLeadingZeros(int i) {
        if (i == 0) {
            return 32;
        }
        int n = 0;
        while (i > 0) {
            i <<= 1;
            n++;
        }
        return n;
    }

    public static int numberOfTrailingZeros(int i) {
        if (i == 0) {
            return 32;
        }
        int n = 0;
        while ((i & 1) == 0) {
            i >>>= 1;
            n++;
        }
        return n;
    }

    public static int highestOneBit(int i) {
        return i & (MIN_VALUE >>> numberOfLeadingZeros(i));
    }

    public static int lowestOneBit(int i) {
        return i & -i;
    }

    public static int rotateLeft(int i, int distance) {
        return (i << distance) | (i >>> -distance);
    }

    public static int rotateRight(int i, int distance) {
        return (i >>> distance) | (i << -distance);
    }

    public static int reverseBytes(int i) {
        return (i << 24) | ((i & 0xff00) << 8) | ((i >>> 8) & 0xff00) | (i >>> 24);
    }
}
