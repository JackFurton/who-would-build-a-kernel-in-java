package duke.conformance;

final class Statics {

    private static int counter;
    private static long big;
    private static byte smallByte;
    private static char character;
    private static short shortValue;
    private static boolean flag;
    private static int initialized = computeInitial();
    private static final String GREETING = "hi";

    private static int computeInitial() {
        return 6 * 7;
    }

    static int postIncrement() {
        counter = 5;
        int before = counter++;
        return before * 100 + counter;
    }

    static long longField() {
        big = 1L << 40;
        big += big;
        return big;
    }

    static int byteFieldWraps() {
        smallByte = (byte) 200;
        return smallByte;
    }

    static int charFieldIsUnsigned() {
        character = (char) -1;
        return character;
    }

    static int shortFieldSignExtends() {
        shortValue = (short) 0x8001;
        return shortValue;
    }

    static boolean booleanField() {
        flag = !flag;
        return flag;
    }

    static int staticInitializerRan() {
        return initialized;
    }

    static int chainedAssignment() {
        int a;
        int b;
        a = b = counter = 9;
        return a + b + counter;
    }

    static int constantString() {
        return GREETING.length();
    }
}
