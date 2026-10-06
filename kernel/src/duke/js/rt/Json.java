package duke.js.rt;

/** {@code JSON.parse} and {@code JSON.stringify}. Numbers are integers, so a fraction is a SyntaxError. */
final class Json {

    private Json() {
    }

    private static JsObject plain() {
        JsObject o = new JsObject();
        o.proto = Globals.objectPrototype();
        return o;
    }

    // ---- parse ----

    static Object parse(String text, Object reviver) {
        Parser p = new Parser(text);
        p.skip();
        Object value = p.value();
        p.skip();
        if (p.at < text.length()) {
            throw p.error();
        }
        if (reviver instanceof JsFunction) {
            JsObject holder = plain();
            holder.set("", value);
            return revive(holder, "", reviver);
        }
        return value;
    }

    private static Object revive(Object holder, String key, Object reviver) {
        Object value = JS.get(holder, key);
        if (value instanceof JsArray) {
            JsArray a = (JsArray) value;
            for (int i = 0; i < a.length(); i++) {
                Object v = revive(a, String.valueOf(i), reviver);
                a.set(i, v);
            }
        } else if (value instanceof JsObject) {
            JsObject o = (JsObject) value;
            String[] keys = o.keys();
            for (int i = 0; i < keys.length; i++) {
                Object v = revive(o, keys[i], reviver);
                if (v == null) {
                    o.remove(keys[i]);
                } else {
                    o.set(keys[i], v);
                }
            }
        }
        return JS.callMethod(reviver, holder, key, value);
    }

    private static final class Parser {
        private final String s;
        int at;

        Parser(String s) {
            this.s = s;
        }

        JsError error() {
            if (at >= s.length()) {
                return new JsError("SyntaxError: Unexpected end of JSON input");
            }
            return new JsError("SyntaxError: Unexpected token '" + s.charAt(at) + "', JSON is not valid at position " + at);
        }

        void skip() {
            while (at < s.length()) {
                char c = s.charAt(at);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    at++;
                } else {
                    break;
                }
            }
        }

        Object value() {
            if (at >= s.length()) {
                throw error();
            }
            char c = s.charAt(at);
            if (c == '{') {
                return object();
            } else if (c == '[') {
                return array();
            } else if (c == '"') {
                return string();
            } else if (c == '-' || c >= '0' && c <= '9') {
                return number();
            } else if (literal("true")) {
                return Boolean.TRUE;
            } else if (literal("false")) {
                return Boolean.FALSE;
            } else if (literal("null")) {
                return JS.NULL;
            }
            throw error();
        }

        private boolean literal(String word) {
            if (s.substring(at).startsWith(word)) {
                at += word.length();
                return true;
            }
            return false;
        }

        private Object number() {
            int start = at;
            if (s.charAt(at) == '-') {
                at++;
            }
            int digits = at;
            while (at < s.length() && s.charAt(at) >= '0' && s.charAt(at) <= '9') {
                at++;
            }
            if (at == digits || s.charAt(digits) == '0' && at - digits > 1) {
                at = digits;
                throw error();
            }
            if (at < s.length() && (s.charAt(at) == '.' || s.charAt(at) == 'e' || s.charAt(at) == 'E')) {
                throw new JsError("SyntaxError: floating-point numbers are not supported");
            }
            return Long.valueOf(Long.parseLong(s.substring(start, at)));
        }

        private String string() {
            at++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (at >= s.length()) {
                    throw error();
                }
                char c = s.charAt(at++);
                if (c == '"') {
                    return sb.toString();
                } else if (c < ' ') {
                    at--;
                    throw error();
                } else if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (at >= s.length()) {
                    throw error();
                }
                char e = s.charAt(at++);
                if (e == 'n') {
                    sb.append('\n');
                } else if (e == 't') {
                    sb.append('\t');
                } else if (e == 'r') {
                    sb.append('\r');
                } else if (e == 'b') {
                    sb.append('\b');
                } else if (e == 'f') {
                    sb.append('\f');
                } else if (e == '"' || e == '\\' || e == '/') {
                    sb.append(e);
                } else if (e == 'u') {
                    int code = 0;
                    for (int i = 0; i < 4; i++) {
                        int d = at < s.length() ? Character.digit(s.charAt(at), 16) : -1;
                        if (d < 0) {
                            throw error();
                        }
                        code = code * 16 + d;
                        at++;
                    }
                    if (code > 0xFF) {
                        throw new JsError("SyntaxError: only Latin-1 characters are supported");
                    }
                    sb.append((char) code);
                } else {
                    at--;
                    throw error();
                }
            }
        }

        private Object array() {
            at++;
            JsArray out = new JsArray();
            skip();
            if (at < s.length() && s.charAt(at) == ']') {
                at++;
                return out;
            }
            while (true) {
                skip();
                out.add(value());
                skip();
                if (at < s.length() && s.charAt(at) == ',') {
                    at++;
                } else if (at < s.length() && s.charAt(at) == ']') {
                    at++;
                    return out;
                } else {
                    throw error();
                }
            }
        }

        private Object object() {
            at++;
            JsObject out = plain();
            skip();
            if (at < s.length() && s.charAt(at) == '}') {
                at++;
                return out;
            }
            while (true) {
                skip();
                if (at >= s.length() || s.charAt(at) != '"') {
                    throw error();
                }
                String key = string();
                skip();
                if (at >= s.length() || s.charAt(at) != ':') {
                    throw error();
                }
                at++;
                skip();
                out.set(key, value());
                skip();
                if (at < s.length() && s.charAt(at) == ',') {
                    at++;
                } else if (at < s.length() && s.charAt(at) == '}') {
                    at++;
                    return out;
                } else {
                    throw error();
                }
            }
        }
    }

    // ---- stringify ----

    static Object stringify(Object value, Object replacer, Object space) {
        String indent = "";
        if (space instanceof Long) {
            long n = Math.min(10, ((Long) space).longValue());
            for (long i = 0; i < n; i++) {
                indent += " ";
            }
        } else if (space instanceof String) {
            String sp = (String) space;
            indent = sp.length() > 10 ? sp.substring(0, 10) : sp;
        }
        JsArray allow = null;
        if (replacer instanceof JsArray) {
            allow = (JsArray) replacer;
        }
        JsObject holder = plain();
        holder.set("", value);
        StringBuilder out = new StringBuilder();
        Writer w = new Writer(out, replacer instanceof JsFunction ? replacer : null, allow, indent);
        return w.write(holder, "", value, "") ? out.toString() : null;
    }

    static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') {
                sb.append("\\\"");
            } else if (c == '\\') {
                sb.append("\\\\");
            } else if (c == '\n') {
                sb.append("\\n");
            } else if (c == '\r') {
                sb.append("\\r");
            } else if (c == '\t') {
                sb.append("\\t");
            } else if (c == '\b') {
                sb.append("\\b");
            } else if (c == '\f') {
                sb.append("\\f");
            } else if (c < ' ') {
                sb.append("\\u00").append("0123456789abcdef".charAt(c >> 4)).append("0123456789abcdef".charAt(c & 15));
            } else {
                sb.append(c);
            }
        }
        sb.append('"');
    }

    private static final class Writer {
        private final StringBuilder out;
        private final Object replacer;
        private final JsArray allow;
        private final String indent;
        private final java.util.ArrayList<Object> stack = new java.util.ArrayList<>();

        Writer(StringBuilder out, Object replacer, JsArray allow, String indent) {
            this.out = out;
            this.replacer = replacer;
            this.allow = allow;
            this.indent = indent;
        }

        /** Appends the JSON for {@code value}, or nothing (returning false) when it has none. */
        boolean write(Object holder, String key, Object value, String current) {
            if (value instanceof JsObject || value instanceof JsArray) {
                Object toJson = JS.get(value, "toJSON");
                if (toJson instanceof JsFunction) {
                    value = JS.callMethod(toJson, value, key);
                }
            }
            if (replacer != null) {
                value = JS.callMethod(replacer, holder, key, value);
            }
            if (value == null || value instanceof JsFunction || value instanceof JsSymbol) {
                return false;
            }
            if (value == JS.NULL) {
                out.append("null");
            } else if (value instanceof Boolean || value instanceof Long) {
                out.append(JS.str(value));
            } else if (value instanceof String) {
                quote(out, (String) value);
            } else if (value instanceof JsArray) {
                array((JsArray) value, current);
            } else if (value instanceof JsObject) {
                object((JsObject) value, current);
            } else {
                out.append("{}");
            }
            return true;
        }

        private void enter(Object o) {
            for (int i = 0; i < stack.size(); i++) {
                if (stack.get(i) == o) {
                    throw new JsError("TypeError: Converting circular structure to JSON");
                }
            }
            stack.add(o);
        }

        private void newline(String level) {
            if (!indent.isEmpty()) {
                out.append('\n').append(level);
            }
        }

        private void array(JsArray a, String current) {
            enter(a);
            String inner = current + indent;
            out.append('[');
            for (int i = 0; i < a.length(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                newline(inner);
                if (!write(a, String.valueOf(i), a.get(i), inner)) {
                    out.append("null");
                }
            }
            if (a.length() > 0) {
                newline(current);
            }
            out.append(']');
            stack.remove(stack.size() - 1);
        }

        private void object(JsObject o, String current) {
            enter(o);
            String inner = current + indent;
            out.append('{');
            String[] keys = o.keys();
            if (allow != null) {
                java.util.ArrayList<String> picked = new java.util.ArrayList<>();
                for (int i = 0; i < allow.length(); i++) {
                    String k = JS.str(allow.get(i));
                    if (o.has(k) && !picked.contains(k)) {
                        picked.add(k);
                    }
                }
                keys = new String[picked.size()];
                for (int i = 0; i < keys.length; i++) {
                    keys[i] = picked.get(i);
                }
            }
            boolean first = true;
            for (int i = 0; i < keys.length; i++) {
                int mark = out.length();
                if (!first) {
                    out.append(',');
                }
                newline(inner);
                quote(out, keys[i]);
                out.append(indent.isEmpty() ? ":" : ": ");
                if (write(o, keys[i], o.get(keys[i]), inner)) {
                    first = false;
                } else {
                    out.setLength(mark);
                }
            }
            if (!first) {
                newline(current);
            }
            out.append('}');
            stack.remove(stack.size() - 1);
        }
    }
}
