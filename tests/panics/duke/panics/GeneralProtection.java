// expect: PANIC: #GP general protection (vector 13, error 0x0)
package duke.panics;

import duke.kernel.Serial;
import duke.kernel.x86.Idt;
import duke.rt.Magic;

final class GeneralProtection {
    static void main() {
        Serial.init();
        Idt.load();
        // Non-canonical: bits 63..47 aren't all equal.
        Magic.peekLong(0x8000_0000_0000_0000L);
    }
}
