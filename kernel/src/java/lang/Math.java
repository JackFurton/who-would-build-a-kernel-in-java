package java.lang;

/** Integer operations only until floating point lands (#26). */
public final class Math {

    private Math() {
    }

    public static int abs(int a) {
        return a < 0 ? -a : a;
    }

    public static long abs(long a) {
        return a < 0 ? -a : a;
    }

    public static int min(int a, int b) {
        return a <= b ? a : b;
    }

    public static long min(long a, long b) {
        return a <= b ? a : b;
    }

    public static int max(int a, int b) {
        return a >= b ? a : b;
    }

    public static long max(long a, long b) {
        return a >= b ? a : b;
    }

    public static int floorDiv(int x, int y) {
        int q = x / y;
        return (x % y != 0 && (x ^ y) < 0) ? q - 1 : q;
    }

    public static long floorDiv(long x, long y) {
        long q = x / y;
        return (x % y != 0 && (x ^ y) < 0) ? q - 1 : q;
    }

    public static int floorMod(int x, int y) {
        int m = x % y;
        return (m != 0 && (m ^ y) < 0) ? m + y : m;
    }

    public static long floorMod(long x, long y) {
        long m = x % y;
        return (m != 0 && (m ^ y) < 0) ? m + y : m;
    }

    public static int clamp(long value, int min, int max) {
        return (int) min(max, max(value, min));
    }
}
