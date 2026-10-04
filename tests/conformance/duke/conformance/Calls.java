package duke.conformance;

final class Calls {

    static long mixedArguments() {
        return mix(1, 2_000_000_000_000L, 3, -4L, (byte) 5, 'z', (short) -7, true);
    }

    private static long mix(int a, long b, int c, long d, byte e, char f, short g, boolean h) {
        return a + b * 10 + c * 100 + d * 1000 + e * 10000 + f + g + (h ? 1 : 0);
    }

    static int argumentsAreCopies() {
        int x = 5;
        clobber(x);
        return x;
    }

    private static int clobber(int x) {
        x = 99;
        return x;
    }

    // Each frame here is ~40 bytes and the boot stack is 64 KiB with no guard page yet.
    static long recursionDepth() {
        return depth(500);
    }

    private static long depth(int n) {
        return n == 0 ? 0 : 1 + depth(n - 1);
    }
}
