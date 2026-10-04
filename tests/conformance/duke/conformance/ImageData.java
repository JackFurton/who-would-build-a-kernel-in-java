package duke.conformance;

/** Classes whose initializers only build constants; dukec runs these at build time. */
final class ImageData {

    static final class Tables {
        static final long[] LONGS = {1, -2, 1L << 40, Long.MIN_VALUE};
        static final int[] INTS = {7, -8, Integer.MAX_VALUE};
        static final byte[] BYTES = {(byte) 200, 1, -1};
        static final char[] CHARS = {'d', 'u', 'k', 'e'};
        static final short[] SHORTS = {(short) 40000, -3};
        static final boolean[] FLAGS = {true, false, true};
        static final String[] NAMES = {"alpha", null, "gamma"};
        static final int[][] GRID = {{1, 2}, {3}, {}};
        static final int[] EMPTY = new int[4];
        static final int COUNT = INTS.length;
        static final String GREETING = NAMES[0];
        static int mutable = 5;
    }

    static long longs() {
        long sum = 0;
        for (long v : Tables.LONGS) {
            sum = sum * 31 + v;
        }
        return sum;
    }

    static int narrowArrays() {
        int sum = 0;
        for (byte b : Tables.BYTES) {
            sum = sum * 31 + b;
        }
        for (char c : Tables.CHARS) {
            sum = sum * 31 + c;
        }
        for (short s : Tables.SHORTS) {
            sum = sum * 31 + s;
        }
        for (boolean f : Tables.FLAGS) {
            sum = sum * 31 + (f ? 1 : 0);
        }
        return sum;
    }

    static String stringsAndNulls() {
        return Tables.NAMES[0] + "," + Tables.NAMES[1] + "," + Tables.NAMES[2] + "," + Tables.GREETING;
    }

    static int nestedArrays() {
        return Tables.GRID.length * 100 + Tables.GRID[0][1] * 10 + Tables.GRID[1][0] + Tables.GRID[2].length;
    }

    static int defaultsAndDerivedStatics() {
        return Tables.EMPTY.length * 100 + Tables.EMPTY[3] + Tables.COUNT * 10;
    }

    static int arraysStayMutable() {
        Tables.INTS[0] += 100;
        Tables.mutable *= 3;
        return Tables.INTS[0] + Tables.mutable;
    }

    static String classOfImageArrays() {
        return Tables.LONGS.getClass().getName() + " " + Tables.NAMES.getClass().getName() + " "
                + Tables.GRID.getClass().getName();
    }
}
