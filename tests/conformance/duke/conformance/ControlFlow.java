package duke.conformance;

final class ControlFlow {

    static int loopSum() {
        int sum = 0;
        for (int i = 1; i <= 100; i++) {
            sum += i;
        }
        return sum;
    }

    static int nestedLoops() {
        int count = 0;
        for (int i = 0; i < 10; i++) {
            for (int j = i; j < 10; j++) {
                if ((i + j) % 3 == 0) {
                    continue;
                }
                count += j;
            }
        }
        return count;
    }

    static int whileBreak() {
        int n = 0;
        while (true) {
            n += 7;
            if (n > 50) {
                break;
            }
        }
        return n;
    }

    static int fib() {
        return fib(20);
    }

    private static int fib(int n) {
        return n < 2 ? n : fib(n - 1) + fib(n - 2);
    }

    static boolean evenOdd() {
        return isEven(10) && !isEven(7);
    }

    private static boolean isEven(int n) {
        return n == 0 || isOdd(n - 1);
    }

    private static boolean isOdd(int n) {
        return n != 0 && isEven(n - 1);
    }

    static int denseSwitch() {
        int total = 0;
        for (int i = -1; i < 8; i++) {
            switch (i) {
                case 0 -> total += 1;
                case 1 -> total += 10;
                case 2 -> total += 100;
                case 3, 4 -> total += 1000;
                case 5 -> total += 10000;
                default -> total += 100000;
            }
        }
        return total;
    }

    static int sparseSwitch() {
        int total = 0;
        for (int i = 0; i < 4; i++) {
            int key = i == 0 ? -1000 : i == 1 ? 7 : i == 2 ? 1 << 20 : 3;
            switch (key) {
                case -1000:
                    total += 1;
                    break;
                case 7:
                    total += 20;
                    // fall through
                case 1 << 20:
                    total += 300;
                    break;
                default:
                    total += 4000;
            }
        }
        return total;
    }

    static int conditionals() {
        int r = 0;
        for (int i = -3; i <= 3; i++) {
            if (i < 0) r += 1;
            if (i <= 0) r += 10;
            if (i > 0) r += 100;
            if (i >= 0) r += 1000;
            if (i == 0) r += 10000;
            if (i != 0) r += 100000;
        }
        return r;
    }

    static boolean referenceCompare() {
        String a = "same";
        String b = "same";
        String c = null;
        return a == b && c == null && a != null;
    }
}
