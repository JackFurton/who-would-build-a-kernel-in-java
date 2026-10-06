package duke.js.rt;

import java.util.ArrayList;

/** Formats values for console.log the way node does, as far as plain data goes. */
final class Inspect {

    /** Depth of the most recently formatted container; node uses it to decide when to stay on one line. */
    private static int lastDepth;

    private Inspect() {
    }

    static String line(Object[] args) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(args[i] instanceof String ? (String) args[i] : format(args[i], 0));
        }
        return sb.toString();
    }

    static String format(Object v, int depth) {
        if (v instanceof String) {
            return quote((String) v);
        }
        if (v instanceof JsArray) {
            JsArray a = (JsArray) v;
            if (depth > 2) {
                return "[Array]";
            }
            if (a.length() == 0) {
                return "[]";
            }
            lastDepth = depth;
            ArrayList<String> parts = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) {
                parts.add(format(a.get(i), depth + 1));
            }
            return combine(parts, "[", "]", depth);
        }
        if (v instanceof JsObject) {
            JsObject o = (JsObject) v;
            if (depth > 2) {
                return "[Object]";
            }
            if (o.size() == 0) {
                return "{}";
            }
            lastDepth = depth;
            ArrayList<String> parts = new ArrayList<>();
            for (int i = 0; i < o.size(); i++) {
                String key = o.keyAt(i);
                parts.add((isIdentifier(key) ? key : quote(key)) + ": " + format(o.valueAt(i), depth + 1));
            }
            return combine(parts, "{", "}", depth);
        }
        if (v instanceof JsFunction) {
            return "[Function (anonymous)]";
        }
        return JS.str(v);
    }

    /** One line if it fits in 72 columns and nests at most three deep, else one entry per line. */
    private static String combine(ArrayList<String> parts, String open, String close, int depth) {
        int n = parts.size();
        if (lastDepth - depth < 3) {
            int total = n + n + 2 * depth + open.length() + 10;
            for (int i = 0; i < n; i++) {
                total += parts.get(i).length();
            }
            if (total <= 80) {
                String joined = join(parts, ", ");
                if (Builtins.indexOf(joined, "\n", 0) < 0) {
                    return open + " " + joined + " " + close;
                }
            }
        }
        StringBuilder indent = new StringBuilder("\n");
        for (int i = 0; i < 2 * depth; i++) {
            indent.append(' ');
        }
        return open + indent + "  " + join(parts, "," + indent + "  ") + indent + close;
    }

    private static String join(ArrayList<String> parts, String separator) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                sb.append(separator);
            }
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    private static boolean isIdentifier(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean letter = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == '_' || c == '$';
            if (!letter && !(i > 0 && c >= '0' && c <= '9')) {
                return false;
            }
        }
        return true;
    }

    private static String quote(String s) {
        char mark = '\'';
        if (Builtins.indexOf(s, "'", 0) >= 0 && Builtins.indexOf(s, "\"", 0) < 0) {
            mark = '"';
        }
        StringBuilder sb = new StringBuilder();
        sb.append(mark);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n') {
                sb.append("\\n");
            } else if (c == '\t') {
                sb.append("\\t");
            } else if (c == '\\') {
                sb.append("\\\\");
            } else if (c == mark) {
                sb.append('\\').append(c);
            } else {
                sb.append(c);
            }
        }
        return sb.append(mark).toString();
    }
}
