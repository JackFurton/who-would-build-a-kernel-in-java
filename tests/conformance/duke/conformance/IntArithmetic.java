package duke.conformance;

final class IntArithmetic {

    static int add() { return opaque(Integer_MAX) + 1; }
    static int sub() { return opaque(Integer_MIN) - 1; }
    static int mul() { return opaque(123456789) * 987654321; }
    static int div() { return opaque(-7) / 2; }
    static int rem() { return opaque(-7) % 2; }
    static int remNegativeDivisor() { return opaque(7) % -3; }
    static int divMinByMinusOne() { return opaque(Integer_MIN) / opaque(-1); }
    static int remMinByMinusOne() { return opaque(Integer_MIN) % opaque(-1); }
    static int divByMinusOne() { return opaque(42) / opaque(-1); }
    static int neg() { return -opaque(5); }
    static int negMin() { return -opaque(Integer_MIN); }
    static int shl() { return opaque(1) << 31; }
    static int shlMasksCount() { return opaque(1) << opaque(33); }
    static int shr() { return opaque(-256) >> 4; }
    static int ushr() { return opaque(-256) >>> 4; }
    static int ushrNegativeCount() { return opaque(-1) >>> opaque(-1); }
    static int and() { return opaque(0xF0F0) & 0x0FF0; }
    static int or() { return opaque(0xF000) | 0x000F; }
    static int xor() { return opaque(0xFFFF) ^ 0x0F0F; }
    static int increment() {
        int x = opaque(10);
        x += 100;
        x -= 1000;
        x++;
        return x;
    }
    static int largeIncrement() {
        int x = opaque(0);
        x += 100000;
        return x;
    }
    static int compound() { return (opaque(3) * 4 + 5) * (opaque(6) - 7) / 2; }

    // javac folds compile-time constants, so route operands through a call.
    static int opaque(int v) { return v; }

    static final int Integer_MAX = 0x7fffffff;
    static final int Integer_MIN = 0x80000000;
}
