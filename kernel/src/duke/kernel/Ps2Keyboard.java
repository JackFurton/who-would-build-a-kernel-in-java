package duke.kernel;

import duke.kernel.x86.Interrupts;
import duke.kernel.x86.IoApic;
import duke.kernel.x86.LocalApic;
import duke.rt.Magic;

/** The PS/2 keyboard on IRQ 1, decoding scan code set 1 (what the i8042 translates to) for a US layout. */
public final class Ps2Keyboard {

    public static final int VECTOR = 0x21;

    private static final int DATA = 0x60;
    private static final int STATUS = 0x64;
    private static final int OUTPUT_FULL = 1;

    private static final int LEFT_SHIFT = 0x2A;
    private static final int RIGHT_SHIFT = 0x36;
    private static final int CONTROL = 0x1D;
    private static final int CAPS_LOCK = 0x3A;
    private static final int EXTENDED = 0xE0;
    private static final int RELEASE = 0x80;

    // Index is the make code; 0 means no character.
    private static final String PLAIN = "\0\u001b1234567890-=\b\tqwertyuiop[]\n\0asdfghjkl;'`\0\\zxcvbnm,./\0*\0 ";
    private static final String SHIFTED = "\0\u001b!@#$%^&*()_+\b\tQWERTYUIOP{}\n\0ASDFGHJKL:\"~\0|ZXCVBNM<>?\0*\0 ";

    private static boolean shift;
    private static boolean control;
    private static boolean capsLock;
    private static boolean extended;

    private Ps2Keyboard() {
    }

    public static void init() {
        // Drain anything the firmware left in the output buffer before taking interrupts.
        while ((Magic.inb(STATUS) & OUTPUT_FULL) != 0) {
            Magic.inb(DATA);
        }
        Interrupts.register(VECTOR, frame -> {
            onScanCode(Magic.inb(DATA));
            LocalApic.endOfInterrupt();
        });
        IoApic.routeIrq(1, VECTOR);
    }

    private static void onScanCode(int code) {
        if (code == EXTENDED) {
            extended = true;
            return;
        }
        boolean released = (code & RELEASE) != 0;
        int key = code & ~RELEASE;
        if (extended) {
            // Arrows, keypad enter and the right-hand modifiers: not mapped yet.
            extended = false;
            if (key == CONTROL) {
                control = !released;
            }
            return;
        }
        switch (key) {
            case LEFT_SHIFT, RIGHT_SHIFT -> shift = !released;
            case CONTROL -> control = !released;
            case CAPS_LOCK -> {
                if (!released) {
                    capsLock = !capsLock;
                }
            }
            default -> {
                if (!released && key < PLAIN.length()) {
                    char c = shift ? SHIFTED.charAt(key) : PLAIN.charAt(key);
                    if (capsLock && Character.isLetter(c)) {
                        c = shift ? Character.toLowerCase(c) : Character.toUpperCase(c);
                    }
                    if (control && Character.isLetter(c)) {
                        c = (char) (Character.toLowerCase(c) - 'a' + 1);
                    }
                    if (c != 0) {
                        Input.put(c);
                    }
                }
            }
        }
    }
}
