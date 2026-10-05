package duke.kernel;

/** Kernel text output: the serial port always, the framebuffer once it's up. */
public final class Console {

    private Console() {
    }

    public static void write(int c) {
        if (c == '\n') {
            Serial.write('\r');
        }
        Serial.write(c);
        FramebufferConsole.write(c);
    }

    public static void print(String s) {
        for (int i = 0; i < s.length(); i++) {
            write(s.charAt(i));
        }
    }

    public static void println(String s) {
        print(s);
        write('\n');
    }

    public static void print(long value) {
        // Work in negatives: Long.MIN_VALUE has no positive counterpart.
        if (value < 0) {
            write('-');
        } else {
            value = -value;
        }
        long divisor = 1;
        while (value / divisor <= -10) {
            divisor *= 10;
        }
        while (divisor > 0) {
            write('0' - (int) (value / divisor));
            value %= divisor;
            divisor /= 10;
        }
    }

    public static void printHex(long value) {
        print(hex(value));
    }

    /** Formats the 64-bit value with a 0x prefix and sixteen lowercase hexadecimal digits. */
    public static String hex(long value) {
        StringBuilder result = new StringBuilder(18);
        result.append("0x");
        for (int shift = 60; shift >= 0; shift -= 4) {
            int digit = (int) ((value >>> shift) & 0xF);
            result.append((char) (digit < 10 ? '0' + digit : 'a' + digit - 10));
        }
        return result.toString();
    }
}
