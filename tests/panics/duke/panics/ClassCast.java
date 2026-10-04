// expect: PANIC: ClassCastException: duke.panics.ClassCast$A cannot be cast to duke.panics.ClassCast$B
package duke.panics;

import duke.kernel.Serial;

final class ClassCast {
    static class A {
    }

    static final class B extends A {
    }

    static void main() {
        Serial.init();
        Object o = new A();
        B b = (B) o;
    }
}
