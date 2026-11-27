package duke.kernel.user;

import duke.kernel.Console;
import duke.kernel.FramebufferConsole;
import duke.kernel.Input;
import duke.kernel.Shell;
import duke.rt.Magic;
import duke.rt.Tib;

/** The console as a file: reads take a line typed at the keyboard, writes go to the screen. */
final class Terminal extends OpenFile {

    // A line typed at the console that a read didn't take all of, kept for the next read.
    private byte[] pendingLine;
    private int pendingAt;

    /**
     * A line at a time, as a terminal in canonical mode gives it: the shell's line editor echoes and
     * edits it, and the read returns once Enter is pressed.
     */
    @Override
    synchronized long read(long buffer, long count) {
        if (pendingLine == null) {
            String typed = Shell.readLine();
            if (typed == null) {
                return -SystemCalls.EINTR;
            }
            String line = typed + "\n";
            pendingLine = new byte[line.length()];
            for (int i = 0; i < line.length(); i++) {
                pendingLine[i] = (byte) line.charAt(i);
            }
            pendingAt = 0;
        }
        long n = Math.min(count, pendingLine.length - pendingAt);
        if (!SystemCalls.putBytes(buffer, pendingLine, pendingAt, n)) {
            return -SystemCalls.EFAULT;
        }
        pendingAt += (int) n;
        if (pendingAt == pendingLine.length) {
            pendingLine = null;
        }
        return n;
    }

    @Override
    long write(long buffer, long count) {
        byte[] chunk = new byte[(int) Math.min(count, 4096)];
        for (long done = 0; done < count; done += chunk.length) {
            int n = (int) Math.min(chunk.length, count - done);
            if (Magic.copyUserMemory(Magic.addressOf(chunk) + Tib.ARRAY_DATA, buffer + done, n) != 0) {
                return done > 0 ? done : -SystemCalls.EFAULT;
            }
            for (int i = 0; i < n; i++) {
                Console.write(chunk[i] & 0xFF);
            }
        }
        return count;
    }

    @Override
    int mode() {
        return MODE_CHARACTER;
    }

    private static final int TCGETS = 0x5401;
    private static final int TCSETS = 0x5402;
    private static final int TCSETSF = 0x5404;
    private static final int TIOCGWINSZ = 0x5413;
    /** The kernel's struct termios: four flag words, c_line, and 19 control characters. */
    private static final int TERMIOS_BYTES = 36;
    private static final int ICRNL = 0400;
    private static final int OPOST = 01;
    private static final int ONLCR = 04;
    private static final int CS8 = 060;
    private static final int CREAD = 0200;
    private static final int B38400 = 017;
    private static final int ISIG = 01;
    private static final int ICANON = 02;
    private static final int ECHO = 010;
    private static final int ECHOE = 020;
    private static final int VINTR = 0;
    private static final int VERASE = 2;
    private static final int VMIN = 6;

    /**
     * Says what the console really does: Duke's line editor gives a line at a time, echoed, with
     * backspace and Ctrl-C (which ends the program rather than sending a signal). The control
     * characters it doesn't have, like ^D for end of file, are 0, which is how Linux turns one off.
     * TCSETS can't change any of that, so it's taken only when it asks for the same.
     */
    @Override
    long ioctl(long request, long argument) {
        if (request == TCGETS) {
            if (!SystemCalls.userWritable(argument, TERMIOS_BYTES)) {
                return -SystemCalls.EFAULT;
            }
            byte[] termios = new byte[TERMIOS_BYTES];
            putInt(termios, 0, ICRNL);
            putInt(termios, 4, OPOST | ONLCR);
            putInt(termios, 8, B38400 | CS8 | CREAD);
            putInt(termios, 12, ISIG | ICANON | ECHO | ECHOE);
            termios[17 + VINTR] = Input.CTRL_C;
            termios[17 + VERASE] = 0x7F;
            termios[17 + VMIN] = 1;
            return SystemCalls.putBytes(argument, termios, 0, TERMIOS_BYTES) ? 0 : -SystemCalls.EFAULT;
        }
        if (request >= TCSETS && request <= TCSETSF) {
            byte[] termios = SystemCalls.userReadable(argument, TERMIOS_BYTES) ? SystemCalls.getBytes(argument, TERMIOS_BYTES) : null;
            if (termios == null) {
                return -SystemCalls.EFAULT;
            }
            int lflag = termios[12] & 0xFF;
            return (lflag & (ICANON | ECHO)) == (ICANON | ECHO) ? 0 : -SystemCalls.EINVAL;
        }
        if (request == TIOCGWINSZ) {
            if (!SystemCalls.userWritable(argument, 8)) {
                return -SystemCalls.EFAULT;
            }
            // 80x24 when there's no framebuffer, only the serial port.
            int rows = FramebufferConsole.rows() > 0 ? FramebufferConsole.rows() : 24;
            int columns = FramebufferConsole.columns() > 0 ? FramebufferConsole.columns() : 80;
            byte[] winsize = {(byte) rows, (byte) (rows >> 8), (byte) columns, (byte) (columns >> 8), 0, 0, 0, 0};
            return SystemCalls.putBytes(argument, winsize, 0, 8) ? 0 : -SystemCalls.EFAULT;
        }
        return -SystemCalls.ENOTTY;
    }

    private static void putInt(byte[] out, int at, int value) {
        for (int i = 0; i < 4; i++) {
            out[at + i] = (byte) (value >>> 8 * i);
        }
    }
}
