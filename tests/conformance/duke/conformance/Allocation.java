package duke.conformance;

final class Allocation {

    static final class Point {
        final int x;
        final int y;

        Point(int x, int y) {
            this.x = x;
            this.y = y;
        }

        int manhattan() {
            return Math_abs(x) + Math_abs(y);
        }
    }

    static final class Counter {
        int count;
        long total;
        byte small;
        char letter;
        short half;
        boolean flag;
        Object ref;
    }

    static class Base {
        int a = 1;
        long wide = 1L << 33;

        Base() {
            a += 10;
        }
    }

    static class Derived extends Base {
        int b = 2;

        Derived() {
            super();
            b += a;
        }
    }

    static final class Node {
        final int value;
        final Node next;

        Node(int value, Node next) {
            this.value = value;
            this.next = next;
        }
    }

    static int constructorAndMethod() {
        return new Point(3, -4).manhattan();
    }

    static long fieldsStartZeroed() {
        Counter c = new Counter();
        return c.count + c.total + c.small + c.letter + c.half + (c.flag ? 1 : 0) + (c.ref == null ? 0 : 1000);
    }

    // `int before = c.count++` compiles to dup_x1.
    static int postIncrementIntField() {
        Counter c = new Counter();
        c.count = 5;
        int before = c.count++;
        return before * 100 + c.count;
    }

    // ...and the long version to dup2_x1.
    static long postIncrementLongField() {
        Counter c = new Counter();
        c.total = 1L << 40;
        long before = c.total++;
        return before + c.total;
    }

    static int narrowFieldsWrap() {
        Counter c = new Counter();
        c.small = (byte) 200;
        c.letter = (char) -1;
        c.half = (short) 0x8001;
        return c.small + c.letter + c.half;
    }

    static long constructorChain() {
        Derived d = new Derived();
        return d.a * 100 + d.b + d.wide;
    }

    static int linkedList() {
        Node head = null;
        for (int i = 1; i <= 100; i++) {
            head = new Node(i, head);
        }
        int sum = 0;
        for (Node n = head; n != null; n = n.next) {
            sum += n.value;
        }
        return sum;
    }

    static boolean distinctIdentities() {
        Counter a = new Counter();
        Counter b = new Counter();
        a.count = 1;
        return a != b && b.count == 0;
    }

    static boolean referenceFields() {
        Counter c = new Counter();
        Counter other = new Counter();
        c.ref = other;
        return c.ref == other;
    }

    static long manySmallAllocations() {
        long sum = 0;
        for (int i = 0; i < 50_000; i++) {
            Point p = new Point(i, 1);
            sum += p.x + p.y;
        }
        return sum;
    }

    private static int Math_abs(int v) {
        return v < 0 ? -v : v;
    }
}
