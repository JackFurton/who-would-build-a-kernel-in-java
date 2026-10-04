// expect: PANIC: #PF page fault (vector 14, error 0x0) at address 0x0
// expect:   at duke.panics.PageFault.main(PageFault.java:13)
package duke.panics;

import duke.kernel.Serial;
import duke.kernel.x86.Idt;
import duke.rt.Magic;

final class PageFault {
    static void main() {
        Serial.init();
        Idt.load();
        Magic.peekLong(0);
    }
}
