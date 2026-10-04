package duke.conformance;

final class JavaLang {

    static final class Point {
        final int x;
        final int y;

        Point(int x, int y) {
            this.x = x;
            this.y = y;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Point p && p.x == x && p.y == y;
        }

        @Override
        public int hashCode() {
            return 31 * x + y;
        }

        @Override
        public String toString() {
            return "Point(".concat(String.valueOf(x)).concat(", ").concat(String.valueOf(y)).concat(")");
        }
    }

    static String overriddenToString() {
        return new Point(3, -4).toString();
    }

    static int overriddenEqualsAndHashCode() {
        Object a = new Point(1, 2);
        Object b = new Point(1, 2);
        return (a.equals(b) ? 1 : 0) + (a == b ? 10 : 0) + (a.hashCode() == b.hashCode() ? 100 : 0);
    }

    static boolean identityEqualsByDefault() {
        Object a = new Object();
        return a.equals(a) && !a.equals(new Object()) && !a.equals(null);
    }

    static boolean identityHashIsStable() {
        Object a = new Object();
        return a.hashCode() == a.hashCode() && System.identityHashCode(a) == a.hashCode();
    }

    static boolean identityHashIgnoresOverride() {
        Point p = new Point(0, 0);
        return System.identityHashCode(p) != p.hashCode() || p.hashCode() == 0;
    }

    static String className() {
        return new Point(0, 0).getClass().getName();
    }

    static String classLiteralName() {
        return String.class.getName();
    }

    static String arrayClassNames() {
        return new int[0].getClass().getName().concat(" ").concat(new String[0].getClass().getName())
                .concat(" ").concat(new Point[0][0].getClass().getName());
    }

    static String simpleNames() {
        return Point.class.getSimpleName().concat(" ").concat(Point[].class.getSimpleName())
                .concat(" ").concat(Object.class.getSimpleName());
    }

    static boolean getClassMatchesLiteral() {
        return new Point(1, 1).getClass() == Point.class && "s".getClass() == String.class;
    }

    static String classToString() {
        return Point.class.toString().concat(" | ").concat(Runnable2.class.toString());
    }

    interface Runnable2 {
        void run();
    }

    static int classQueries() {
        int bits = 0;
        bits |= Point.class.isInstance(new Point(0, 0)) ? 1 : 0;
        bits |= Object.class.isAssignableFrom(Point.class) ? 2 : 0;
        bits |= Point.class.isAssignableFrom(Object.class) ? 4 : 0;
        bits |= int[].class.isArray() ? 8 : 0;
        bits |= Runnable2.class.isInterface() ? 16 : 0;
        bits |= Point.class.getSuperclass() == Object.class ? 32 : 0;
        bits |= Object.class.getSuperclass() == null ? 64 : 0;
        bits |= String[].class.getComponentType() == String.class ? 128 : 0;
        bits |= Runnable2.class.getSuperclass() == null ? 256 : 0;
        return bits;
    }

    static String stringBasics() {
        String s = "kernel";
        return s.substring(1, 4).concat("|").concat(s.substring(3)).concat("|")
                .concat(String.valueOf(s.indexOf('e'))).concat(String.valueOf(s.lastIndexOf('e')))
                .concat(String.valueOf(s.indexOf('z')));
    }

    static int stringPredicates() {
        String s = "duke kernel";
        int bits = 0;
        bits |= s.startsWith("duke") ? 1 : 0;
        bits |= s.endsWith("kernel") ? 2 : 0;
        bits |= s.startsWith("kernel") ? 4 : 0;
        bits |= "".isEmpty() ? 8 : 0;
        bits |= s.equals("duke ".concat("kernel")) ? 16 : 0;
        bits |= s.equals("duke") ? 32 : 0;
        bits |= s.endsWith("") ? 64 : 0;
        return bits;
    }

    static int stringHashMatchesJdk() {
        return "The quick brown fox".hashCode() ^ "".hashCode() ^ "\u00ff\u0080".hashCode();
    }

    static int stringCompareTo() {
        return Integer.signum("apple".compareTo("banana")) * 100
                + Integer.signum("pear".compareTo("pea")) * 10
                + "same".compareTo("same");
    }

    static String valueOfs() {
        return String.valueOf(true).concat(String.valueOf(false)).concat(String.valueOf('x'))
                .concat(String.valueOf((Object) null)).concat(String.valueOf(-42L));
    }

    static String integerToString() {
        return Integer.toString(0).concat(",").concat(Integer.toString(Integer.MIN_VALUE)).concat(",")
                .concat(Integer.toString(Integer.MAX_VALUE)).concat(",").concat(Long.toString(Long.MIN_VALUE));
    }

    static String hexAndBinary() {
        return Integer.toHexString(-1).concat(",").concat(Integer.toHexString(255)).concat(",")
                .concat(Integer.toBinaryString(10)).concat(",").concat(Long.toHexString(Long.MIN_VALUE)).concat(",")
                .concat(Long.toHexString(0));
    }

    static long parsing() {
        return Integer.parseInt("-2147483648") + Integer.parseInt("+77") + Long.parseLong("9223372036854775807")
                + Integer.parseInt("ff", 16) + Long.parseLong("-1010", 2);
    }

    static long bitTwiddling() {
        long r = 0;
        r = r * 100 + Integer.bitCount(0xF0F0);
        r = r * 100 + Integer.numberOfLeadingZeros(1);
        r = r * 100 + Integer.numberOfTrailingZeros(0x100);
        r = r * 100 + Long.numberOfLeadingZeros(1L << 40);
        r = r * 100 + Long.numberOfTrailingZeros(0);
        r = r * 100 + Long.bitCount(-1L);
        return r ^ Integer.highestOneBit(1000) ^ Integer.lowestOneBit(96) ^ Integer.rotateLeft(0x80000001, 4)
                ^ Integer.reverseBytes(0x01020304) ^ Long.rotateRight(1L, 1);
    }

    static int compares() {
        return Integer.compare(-5, 3) * 100 + Long.compare(7L, 7L) * 10 + Integer.signum(-99)
                + Long.signum(Long.MAX_VALUE) * 1000 + Long.hashCode(1L << 32);
    }

    static long mathOps() {
        long r = 0;
        r = r * 1000 + Math.abs(-17);
        r = r * 1000 + Math.max(3, 9) + Math.min(-3L, 4L);
        r = r * 1000 + Math.floorDiv(-7, 2) + 500;
        r = r * 1000 + Math.floorMod(-7, 2);
        r = r * 1000 + Math.floorMod(7L, -3L) + 500;
        r = r * 1000 + Math.clamp(1000L, 0, 255);
        return r;
    }

    static int absOfMinValueStaysNegative() {
        return Math.abs(Integer.MIN_VALUE);
    }

    static String arraycopyOverlapping() {
        int[] a = {1, 2, 3, 4, 5, 6, 7, 8};
        System.arraycopy(a, 0, a, 2, 5);
        int[] b = {1, 2, 3, 4, 5, 6, 7, 8};
        System.arraycopy(b, 3, b, 1, 5);
        String out = "";
        for (int v : a) {
            out = out.concat(String.valueOf(v));
        }
        out = out.concat("|");
        for (int v : b) {
            out = out.concat(String.valueOf(v));
        }
        return out;
    }

    static int arraycopyReferencesAndLongs() {
        Object[] objects = new Object[3];
        String[] strings = {"a", "bb", "ccc"};
        System.arraycopy(strings, 0, objects, 0, 3);
        long[] longs = {1L << 40, 2, 3};
        long[] copy = new long[3];
        System.arraycopy(longs, 0, copy, 0, 3);
        return ((String) objects[2]).length() + (copy[0] == 1L << 40 ? 100 : 0);
    }
}
