package duke.kernel;

import duke.rt.Magic;

/** 16550 UART on COM1, polled. */
public final class Serial {

    private static final int COM1 = 0x3F8;
    private static final int LINE_STATUS = COM1 + 5;
    private static final int TX_EMPTY = 0x20;

    private Serial() {
    }

    public static void init() {
        Magic.outb(COM1 + 1, 0x00); // no interrupts
        Magic.outb(COM1 + 3, 0x80); // DLAB on
        Magic.outb(COM1 + 0, 0x01); // divisor 1: 115200 baud
        Magic.outb(COM1 + 1, 0x00);
        Magic.outb(COM1 + 3, 0x03); // 8N1, DLAB off
        Magic.outb(COM1 + 2, 0xC7); // FIFO on, cleared, 14-byte threshold
        Magic.outb(COM1 + 4, 0x03); // DTR + RTS
    }

    public static void write(int b) {
        while ((Magic.inb(LINE_STATUS) & TX_EMPTY) == 0) {
            Magic.pause();
        }
        Magic.outb(COM1, b);
    }
}
