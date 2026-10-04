package duke.conformance;

final class Strings {

    static int length() {
        return "hello, world".length();
    }

    static int emptyLength() {
        return "".length();
    }

    static int charAt() {
        return "Duke".charAt(2);
    }

    static int latin1CharIsUnsigned() {
        return "ÿ".charAt(0);
    }

    static int checksum() {
        String s = "The quick brown fox jumps over the lazy dog";
        int h = 0;
        for (int i = 0; i < s.length(); i++) {
            h = 31 * h + s.charAt(i);
        }
        return h;
    }
}
