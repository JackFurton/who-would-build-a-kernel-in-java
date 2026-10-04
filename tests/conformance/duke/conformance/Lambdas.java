package duke.conformance;

final class Lambdas {

    interface IntOp {
        int apply(int x);
    }

    interface Fn<A, R> {
        R apply(A a);
    }

    interface Supplier<T> {
        T get();
    }

    interface LongSource {
        long get();
    }

    interface Action {
        void run();
    }

    interface BiFn<A, B, R> {
        R apply(A a, B b);
    }

    static int nonCapturing() {
        IntOp square = x -> x * x;
        return square.apply(7);
    }

    static long capturesLocalsOfEveryKind() {
        int i = 3;
        long l = 1L << 40;
        String s = "four";
        char c = 'c';
        IntOp op = x -> x + i + (int) (l >> 40) + s.length() + c;
        return op.apply(10);
    }

    private int base = 100;

    private int instanceSum(int x) {
        IntOp addBase = y -> y + base;
        base += 1;
        return addBase.apply(x);
    }

    static int capturesThis() {
        return new Lambdas().instanceSum(5);
    }

    static int staticMethodReference() {
        IntOp bits = Integer::bitCount;
        return bits.apply(0xFF);
    }

    static int boundMethodReference() {
        String s = "kernel";
        IntOp at = s::charAt;
        return at.apply(2);
    }

    static int unboundMethodReference() {
        Fn<String, Integer> length = String::length;
        return length.apply("hello world");
    }

    static final class Point {
        final int x;
        final int y;

        Point(int x, int y) {
            this.x = x;
            this.y = y;
        }
    }

    static int constructorReference() {
        BiFn<Integer, Integer, Point> make = Point::new;
        Point p = make.apply(3, 4);
        return p.x * 10 + p.y;
    }

    static int arrayConstructorReference() {
        Fn<Integer, int[]> make = int[]::new;
        return make.apply(9).length;
    }

    static int genericWithBoxing() {
        Fn<Integer, Integer> inc = x -> x + 1;
        Fn<Integer, Integer> abs = Math::abs;
        return inc.apply(41) * 1000 + abs.apply(-7);
    }

    static long widensIntToLong() {
        LongSource source = Lambdas::seven;
        return source.get() + (1L << 33);
    }

    private static int seven() {
        return 7;
    }

    static int resultDiscardedForVoidInterface() {
        StringBuilder sb = new StringBuilder();
        Action append = () -> sb.append("x");
        Action length = sb::length;
        append.run();
        append.run();
        length.run();
        return sb.length();
    }

    static int captureInLoop() {
        IntOp[] ops = new IntOp[5];
        for (int i = 0; i < ops.length; i++) {
            int k = i;
            ops[i] = x -> x * k;
        }
        int total = 0;
        for (IntOp op : ops) {
            total += op.apply(10);
        }
        return total;
    }

    static int curried() {
        Fn<Integer, Fn<Integer, Integer>> add = a -> b -> a + b;
        return add.apply(30).apply(12);
    }

    static String supplierOfObjects() {
        Supplier<String> greeting = () -> "hi " + 42;
        Supplier<Point> point = () -> new Point(1, 2);
        return greeting.get() + point.get().y;
    }

    static int lambdaAsObject() {
        IntOp op = x -> x;
        Object o = op;
        return (o instanceof IntOp ? 1 : 0) + (o instanceof Action ? 10 : 0);
    }

    static int interfaceMethodReference() {
        BiFn<IntOp, Integer, Integer> call = IntOp::apply;
        IntOp triple = x -> 3 * x;
        return call.apply(triple, 14);
    }

    interface Greeter {
        String name();

        default Supplier<String> greeting() {
            return () -> "hello " + name();
        }
    }

    static String lambdaInDefaultMethodCapturesThis() {
        Greeter g = () -> "duke";
        return g.greeting().get();
    }
}
