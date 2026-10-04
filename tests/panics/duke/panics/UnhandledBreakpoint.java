// expect: PANIC: #BP breakpoint (vector 3, error 0x0)
package duke.panics;

import duke.kernel.Serial;
import duke.kernel.x86.Idt;
import duke.rt.Magic;

final class UnhandledBreakpoint {
    static void main() {
        Serial.init();
        Idt.load();
        Magic.breakpoint();
    }
}
