package duke.kernel;

public final class Console {

    private Console() {
    }

    public static void print(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n') {
                Serial.write('\r');
            }
            Serial.write(c);
        }
    }

    public static void println(String s) {
        print(s);
        print("\n");
    }

    public static void print(long value) {
        // Work in negatives: Long.MIN_VALUE has no positive counterpart.
        if (value < 0) {
            Serial.write('-');
        } else {
            value = -value;
        }
        long divisor = 1;
        while (value / divisor <= -10) {
            divisor *= 10;
        }
        while (divisor > 0) {
            Serial.write('0' - (int) (value / divisor));
            value %= divisor;
            divisor /= 10;
        }
    }
}
