package duke.conformance;

/** Each test touches its own nested classes, so HotSpot and Duke see identical first uses. */
final class ClassInit {

    static long log;

    static int record(int event) {
        log = log * 10 + event;
        return event;
    }

    static final class A1 {
        static int value = record(1);
    }

    static final class B1 {
        static int value = record(2);
    }

    static long firstUseOrder() {
        log = 0;
        int b = B1.value;
        int a = A1.value;
        int again = B1.value + A1.value;
        return log * 1000 + a + b + again;
    }

    static class Parent2 {
        static int p = record(1);
    }

    static final class Child2 extends Parent2 {
        static int c = record(2);

        static int touch() {
            return 9;
        }
    }

    static long superclassFirst() {
        log = 0;
        return Child2.touch() + log * 100;
    }

    static final class Constants3 {
        static final int COMPILE_TIME = 5;
        static int sideEffect = record(7);
    }

    static long constantsDoNotInitialize() {
        log = 0;
        int k = Constants3.COMPILE_TIME;
        Constants3[] array = new Constants3[2];
        return log * 100 + k + array.length;
    }

    static final class Created4 {
        static int created = record(4);
        final int id;

        Created4(int id) {
            this.id = id;
        }
    }

    static long newInitializes() {
        log = 0;
        Created4 c = new Created4(6);
        return log * 100 + c.id;
    }

    static final class Recursive5 {
        static int first = record(5);
        static int second = readsOwnStatic();

        static int readsOwnStatic() {
            return first * 10 + third;
        }

        static int third = 3;
    }

    // `second` reads `third` before its initializer has run, so it sees 0.
    static long selfReferenceSeesDefaults() {
        log = 0;
        return Recursive5.second * 100 + Recursive5.third + log * 10000;
    }

    static final class Cycle6a {
        static int a = record(6) + Cycle6b.b;
    }

    static final class Cycle6b {
        static int b = record(8) + Cycle6a.a;
    }

    // a starts first; b reads a mid-initialization and sees 0.
    static long initializationCycle() {
        log = 0;
        return Cycle6a.a * 1000 + Cycle6b.b + log * 100000;
    }

    static class Parent7 {
        static int p = record(7);
    }

    static final class Child7 extends Parent7 {
        static int c = record(9);
    }

    // Static fields accessed through a subclass name only initialize the declaring class.
    static long inheritedStaticInitializesDeclaringClassOnly() {
        log = 0;
        int p = Child7.p;
        return log * 10 + p;
    }
}
