package duke.js.rt;

/** URI percent-encoding and base64 over the Latin-1 characters the runtime has. */
final class Encoding {

    private static final String HEX = "0123456789ABCDEF";
    private static final String BASE64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

    private Encoding() {
    }

    private static boolean unreserved(char c) {
        return c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || "-_.!~*'()".indexOf(c) >= 0;
    }

    /** Percent-encodes the UTF-8 bytes of {@code s}, leaving unreserved characters and those in {@code keep}. */
    static String encode(String s, String keep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (unreserved(c) || keep.indexOf(c) >= 0) {
                sb.append(c);
            } else if (c < 0x80) {
                percent(sb, c);
            } else if (c <= 0xFF) {
                percent(sb, 0xC0 | (c >> 6));
                percent(sb, 0x80 | (c & 0x3F));
            } else {
                throw new JsError("URIError: URI malformed");
            }
        }
        return sb.toString();
    }

    private static void percent(StringBuilder sb, int b) {
        sb.append('%').append(HEX.charAt(b >> 4)).append(HEX.charAt(b & 15));
    }

    /** Decodes %XX escapes (as UTF-8 bytes); characters in {@code keep} stay escaped. */
    static String decode(String s, String keep) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c != '%') {
                sb.append(c);
                i++;
                continue;
            }
            int first = hexByte(s, i);
            int length = 3;
            int code = first;
            if (first >= 0xC0 && first < 0xE0) {
                code = ((first & 0x1F) << 6) | (hexByte(s, i + 3) & 0x3F);
                length = 6;
            } else if (first >= 0x80) {
                throw new JsError("URIError: URI malformed");
            }
            if (code > 0xFF) {
                throw new JsError("URIError: URI malformed");
            }
            if (keep.indexOf((char) code) >= 0) {
                sb.append(s.substring(i, i + length));
            } else {
                sb.append((char) code);
            }
            i += length;
        }
        return sb.toString();
    }

    private static int hexByte(String s, int at) {
        if (at + 3 > s.length() || s.charAt(at) != '%') {
            throw new JsError("URIError: URI malformed");
        }
        int hi = Character.digit(s.charAt(at + 1), 16);
        int lo = Character.digit(s.charAt(at + 2), 16);
        if (hi < 0 || lo < 0) {
            throw new JsError("URIError: URI malformed");
        }
        return hi * 16 + lo;
    }

    static String btoa(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i += 3) {
            int b0 = byteAt(s, i);
            int b1 = i + 1 < s.length() ? byteAt(s, i + 1) : 0;
            int b2 = i + 2 < s.length() ? byteAt(s, i + 2) : 0;
            sb.append(BASE64.charAt(b0 >> 2));
            sb.append(BASE64.charAt(((b0 & 3) << 4) | (b1 >> 4)));
            sb.append(i + 1 < s.length() ? BASE64.charAt(((b1 & 15) << 2) | (b2 >> 6)) : '=');
            sb.append(i + 2 < s.length() ? BASE64.charAt(b2 & 63) : '=');
        }
        return sb.toString();
    }

    private static int byteAt(String s, int i) {
        char c = s.charAt(i);
        if (c > 0xFF) {
            throw new JsError("InvalidCharacterError: the string contains characters outside of the Latin1 range");
        }
        return c;
    }

    static String atob(String s) {
        StringBuilder sb = new StringBuilder();
        int bits = 0;
        int collected = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '=' || c <= ' ') {
                continue;
            }
            int v = BASE64.indexOf(c);
            if (v < 0) {
                throw new JsError("InvalidCharacterError: the string to be decoded is not correctly encoded");
            }
            bits = (bits << 6) | v;
            collected += 6;
            if (collected >= 8) {
                collected -= 8;
                sb.append((char) ((bits >> collected) & 0xFF));
            }
        }
        return sb.toString();
    }
}
