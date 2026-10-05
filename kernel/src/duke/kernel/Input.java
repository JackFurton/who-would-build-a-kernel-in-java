package duke.kernel;

/**
 * Typed characters from every input device, in arrival order. Interrupt handlers put, the shell
 * takes. One producer at a time (handlers run with interrupts off) and one consumer, so plain
 * head and tail indices are enough. Image arrays, so putting never allocates.
 */
public final class Input {

    public static final int KEY_LEFT   = 0x101;
    public static final int KEY_RIGHT  = 0x102;
    public static final int KEY_HOME   = 0x103;
    public static final int KEY_END    = 0x104;
    public static final int KEY_DELETE = 0x105;

    private static final int[] BUFFER = new int[256];
    private static volatile int head;
    private static volatile int tail;

    private Input() {
    }

    /** Called from interrupt handlers. Drops the character when the buffer is full. */
    public static void put(int c) {
        int next = (tail + 1) & (BUFFER.length - 1);
        if (next != head) {
            BUFFER[tail] = c;
            tail = next;
        }
    }

    /** Waits for the next character, letting other threads run meanwhile. */
    public static int take() {
        while (head == tail) {
            Scheduler.pause();
        }
        int c = BUFFER[head];
        head = (head + 1) & (BUFFER.length - 1);
        return c;
    }
}