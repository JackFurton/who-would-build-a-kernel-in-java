package duke.conformance;

final class Interfaces {

    interface Shape {
        int area();

        default int perimeterHint() {
            return 0;
        }

        static int unit() {
            return 1;
        }
    }

    interface Named {
        int nameLength();

        default int describe() {
            return 1000 + secret();
        }

        private int secret() {
            return nameLength() * 2;
        }
    }

    static final class Square implements Shape, Named {
        final int side;

        Square(int side) {
            this.side = side;
        }

        @Override
        public int area() {
            return side * side;
        }

        @Override
        public int perimeterHint() {
            return 4 * side;
        }

        @Override
        public int nameLength() {
            return 6;
        }
    }

    static final class Blob implements Shape {
        @Override
        public int area() {
            return 7;
        }
    }

    static int callsThroughInterface() {
        Shape[] shapes = {new Square(3), new Blob(), new Square(1)};
        int total = 0;
        for (Shape s : shapes) {
            total = total * 100 + s.area();
        }
        return total;
    }

    static int defaultMethodUsedAndOverridden() {
        Shape square = new Square(2);
        Shape blob = new Blob();
        return square.perimeterHint() * 100 + blob.perimeterHint();
    }

    static int staticInterfaceMethod() {
        return Shape.unit();
    }

    static int privateInterfaceMethod() {
        Named n = new Square(1);
        return n.describe();
    }

    static int secondInterfaceOnSameClass() {
        Square s = new Square(5);
        Shape shape = s;
        Named named = s;
        return shape.area() + named.nameLength();
    }

    interface Base {
        default int level() {
            return 1;
        }
    }

    interface Middle extends Base {
        @Override
        default int level() {
            return 10 + Base.super.level();
        }
    }

    static class Leaf implements Middle {
    }

    static final class LeafOverride implements Middle {
        @Override
        public int level() {
            return 100 + Middle.super.level();
        }
    }

    static int mostSpecificDefaultWins() {
        Base b = new Leaf();
        return b.level();
    }

    static int interfaceSuperCall() {
        Base b = new LeafOverride();
        return b.level();
    }

    // javac emits invokevirtual here; the method only exists as Middle's default.
    static int inheritedDefaultThroughClassType() {
        Leaf leaf = new Leaf();
        return leaf.level();
    }

    abstract static class Partial implements Shape {
        @Override
        public int perimeterHint() {
            return 50;
        }
    }

    static final class Completed extends Partial {
        @Override
        public int area() {
            return 9;
        }
    }

    static int abstractClassFillsInterface() {
        Shape s = new Completed();
        return s.area() * 100 + s.perimeterHint();
    }

    interface Box<T> {
        T get();
    }

    static final class StringBox implements Box<String> {
        @Override
        public String get() {
            return "boxed";
        }
    }

    interface Comparator2<T> {
        int compare(T a, T b);
    }

    static final class ByLength implements Comparator2<String> {
        @Override
        public int compare(String a, String b) {
            return a.length() - b.length();
        }
    }

    // Both go through javac-generated bridge methods (get()Object, compare(Object,Object)).
    static int genericBridges() {
        Box<String> box = new StringBox();
        Comparator2<String> cmp = new ByLength();
        return box.get().length() * 100 + cmp.compare("four", "a");
    }

    interface IntOp {
        int apply(int x);
    }

    static int anonymousClassesCapture() {
        int offset = 5;
        IntOp add = new IntOp() {
            @Override
            public int apply(int x) {
                return x + offset;
            }
        };
        IntOp twice = new IntOp() {
            @Override
            public int apply(int x) {
                return add.apply(add.apply(x));
            }
        };
        return twice.apply(1);
    }

    static boolean interfaceTypedNullCheck() {
        Shape s = null;
        return s == null;
    }
}
