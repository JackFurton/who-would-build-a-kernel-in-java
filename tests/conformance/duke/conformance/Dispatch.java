package duke.conformance;

final class Dispatch {

    abstract static class Shape {
        abstract int area();

        int twiceArea() {
            return 2 * area();
        }

        int sides() {
            return 0;
        }
    }

    static final class Square extends Shape {
        final int side;

        Square(int side) {
            this.side = side;
        }

        @Override
        int area() {
            return side * side;
        }

        @Override
        int sides() {
            return 4;
        }
    }

    static class Rect extends Shape {
        final int w;
        final int h;

        Rect(int w, int h) {
            this.w = w;
            this.h = h;
        }

        @Override
        int area() {
            return w * h;
        }

        @Override
        int sides() {
            return 4;
        }
    }

    static final class Tall extends Rect {
        Tall(int w) {
            super(w, w * 3);
        }

        @Override
        int area() {
            return super.area() + 1;
        }
    }

    static class Circle extends Shape {
        @Override
        int area() {
            return 3;
        }
        // inherits sides() == 0
    }

    static int abstractMethodThroughBase() {
        Shape s = new Square(5);
        return s.area();
    }

    static int polymorphicArray() {
        Shape[] shapes = {new Square(2), new Rect(2, 3), new Tall(1), new Circle()};
        int total = 0;
        for (Shape s : shapes) {
            total = total * 100 + s.area();
        }
        return total;
    }

    static int inheritedTemplateMethod() {
        Shape s = new Tall(2);
        return s.twiceArea();
    }

    static int superCallInOverride() {
        Rect r = new Tall(4);
        return r.area();
    }

    static int inheritedImplementationFallsBackToBase() {
        Shape[] shapes = {new Circle(), new Square(1)};
        return shapes[0].sides() * 10 + shapes[1].sides();
    }

    static class Greeter {
        int greet() {
            return 1;
        }

        int greet(int times) {
            return greet() * times;
        }
    }

    static class LoudGreeter extends Greeter {
        @Override
        int greet() {
            return 7;
        }
    }

    static int overloadDispatchesThroughOverride() {
        Greeter g = new LoudGreeter();
        return g.greet(3) * 100 + new Greeter().greet(3);
    }

    static class Base {
        int seen;

        Base() {
            seen = describe();
        }

        int describe() {
            return 1;
        }
    }

    static final class Child extends Base {
        int field = 42;

        @Override
        int describe() {
            return 100 + field;
        }
    }

    // Java dispatches to the override before Child's field initializers have run.
    static int virtualCallFromConstructor() {
        return new Child().seen;
    }

    static class Level1 {
        int id() {
            return 1;
        }
    }

    static class Level2 extends Level1 {
        @Override
        int id() {
            return 10 + super.id();
        }
    }

    static class Level3 extends Level2 {
        @Override
        int id() {
            return 100 + super.id();
        }
    }

    static int deepHierarchy() {
        Level1[] all = {new Level1(), new Level2(), new Level3()};
        int sum = 0;
        for (Level1 l : all) {
            sum = sum * 1000 + l.id();
        }
        return sum;
    }
}
