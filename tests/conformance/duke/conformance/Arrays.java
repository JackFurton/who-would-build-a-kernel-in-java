package duke.conformance;

final class Arrays {

    static long everyPrimitiveWidth() {
        boolean[] z = new boolean[3];
        byte[] b = new byte[3];
        char[] c = new char[3];
        short[] s = new short[3];
        int[] i = new int[3];
        long[] l = new long[3];
        z[1] = true;
        b[1] = (byte) 200;
        c[1] = (char) -1;
        s[1] = (short) 0x8001;
        i[1] = -5;
        l[1] = 1L << 50;
        long sum = 0;
        for (int k = 0; k < 3; k++) {
            sum += (z[k] ? 1 : 0) + b[k] + c[k] + s[k] + i[k] + l[k];
        }
        return sum;
    }

    static int newArraysAreZeroed() {
        int[] a = new int[1000];
        int nonZero = 0;
        for (int k = 0; k < a.length; k++) {
            if (a[k] != 0) {
                nonZero++;
            }
        }
        return nonZero + a.length;
    }

    static int emptyArray() {
        return new long[0].length;
    }

    static int bubbleSort() {
        int[] a = new int[50];
        for (int k = 0; k < a.length; k++) {
            a[k] = (k * 37 + 11) % 50;
        }
        for (int i = 0; i < a.length; i++) {
            for (int j = 0; j + 1 < a.length - i; j++) {
                if (a[j] > a[j + 1]) {
                    int t = a[j];
                    a[j] = a[j + 1];
                    a[j + 1] = t;
                }
            }
        }
        int ordered = 0;
        for (int k = 0; k < a.length; k++) {
            if (a[k] == k) {
                ordered++;
            }
        }
        return ordered;
    }

    static int referenceArray() {
        String[] words = new String[3];
        words[0] = "duke";
        words[2] = "kernel";
        int total = 0;
        for (String w : words) {
            total += w == null ? 100 : w.length();
        }
        return total;
    }

    static int twoDimensional() {
        int[][] grid = new int[4][5];
        for (int r = 0; r < 4; r++) {
            for (int c = 0; c < 5; c++) {
                grid[r][c] = r * 10 + c;
            }
        }
        return grid[3][4] + grid.length * 100 + grid[0].length * 1000;
    }

    static long threeDimensionalPartial() {
        long[][][] cube = new long[2][3][];
        int nulls = 0;
        for (long[][] plane : cube) {
            for (long[] row : plane) {
                if (row == null) {
                    nulls++;
                }
            }
        }
        cube[1][2] = new long[] {7, 8, 9};
        return nulls * 1000 + cube[1][2][2] + cube.length * 10 + cube[0].length;
    }

    static int jagged() {
        int[][] rows = new int[4][];
        for (int r = 0; r < rows.length; r++) {
            rows[r] = new int[r + 1];
            rows[r][r] = r;
        }
        int sum = 0;
        for (int[] row : rows) {
            sum += row.length * 10 + row[row.length - 1];
        }
        return sum;
    }

    static int arrayInitializer() {
        int[] primes = {2, 3, 5, 7, 11, 13};
        int sum = 0;
        for (int p : primes) {
            sum += p;
        }
        return sum;
    }

    // `a[i]++` as a value compiles to dup2 + dup_x2...
    static int postIncrementIntElement() {
        int[] a = new int[2];
        a[1] = 41;
        int before = a[1]++;
        return before * 100 + a[1];
    }

    // ...and for long elements to dup2_x2.
    static long postIncrementLongElement() {
        long[] a = new long[2];
        a[1] = 1L << 40;
        long before = a[1]++;
        return before + a[1];
    }

    static int charArrayChecksum() {
        char[] text = new char[26];
        for (int k = 0; k < text.length; k++) {
            text[k] = (char) ('a' + k);
        }
        int h = 0;
        for (char ch : text) {
            h = h * 31 + ch;
        }
        return h;
    }
}
