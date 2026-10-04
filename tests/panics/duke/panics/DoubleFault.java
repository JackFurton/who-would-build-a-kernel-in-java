// expect: PANIC: #DF double fault (vector 8, error 0x0), likely the stack running into its guard page
// expect:   at duke.panics.DoubleFault.recurse(DoubleFault.java:10)
package duke.panics;

import duke.kernel.Kernel;
import duke.rt.Magic;

final class DoubleFault {
    static int recurse(int n) {
        return recurse(n + 1) + 1; // RECURSE
    }

    static void main() {
        Kernel.init();
        // No prologue checks: the only thing between the stack and the rest of .bss is the guard page.
        Magic.setStackLimit(0);
        recurse(0);
    }
}
