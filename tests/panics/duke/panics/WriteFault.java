// expect: PANIC: #PF page fault (vector 14, error 0x2) at address 0x1000
package duke.panics;

import duke.kernel.Serial;
import duke.kernel.x86.Idt;
import duke.rt.Magic;

final class WriteFault {
    static void main() {
        Serial.init();
        Idt.load();
        Magic.pokeLong(0x1000, 1);
    }
}
