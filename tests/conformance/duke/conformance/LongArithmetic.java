package duke.conformance;

final class LongArithmetic {

    static long add() { return opaque(Long_MAX) + 1; }
    static long sub() { return opaque(Long_MIN) - 1; }
    static long mul() { return opaque(0x123456789L) * 0x987654321L; }
    static long div() { return opaque(-7_000_000_000L) / 3; }
    static long rem() { return opaque(-7_000_000_000L) % 3; }
    static long divMinByMinusOne() { return opaque(Long_MIN) / opaque(-1L); }
    static long remMinByMinusOne() { return opaque(Long_MIN) % opaque(-1L); }
    static long neg() { return -opaque(5_000_000_000L); }
    static long shl() { return opaque(1L) << 63; }
    static long shlMasksCount() { return opaque(1L) << opaque(65); }
    static long shr() { return opaque(-1L << 40) >> 8; }
    static long ushr() { return opaque(-1L) >>> 1; }
    static long and() { return opaque(0xFFFF_0000_FFFF_0000L) & 0x0F0F_0F0F_0F0F_0F0FL; }
    static long or() { return opaque(0xFF00_0000_0000_0000L) | 0xFFL; }
    static long xor() { return opaque(-1L) ^ 0x5555_5555_5555_5555L; }
    static int lcmpLess() { return opaque(-5L) < 3L ? 1 : 0; }
    static int lcmpEqual() { return opaque(7L) == 7L ? 1 : 0; }
    static int lcmpGreaterAcrossSign() { return opaque(Long_MAX) > Long_MIN ? 1 : 0; }

    static long hexDigits() {
        long value = opaque(0x1234_5678_9abc_def0L);
        long result = 0;

        for (int shift = 60; shift >= 0; shift -= 4) {
            long digit = (value >>> shift) & 0xF;
            result = result * 16 + digit;
        }

        return result;
    }

    static long opaque(long v) { return v; }

    static int opaque(int v) { return v; }

    static final long Long_MAX = 0x7fffffffffffffffL;
    static final long Long_MIN = 0x8000000000000000L;
}
