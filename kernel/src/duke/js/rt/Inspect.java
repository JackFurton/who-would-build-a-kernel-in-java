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
        if (v instanceof JsSymbol) {
            return v.toString();
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
            if (Globals.isError(o)) {
                // node prints the stack; there are no stack traces here, so just "Name: message".
                return depth == 0 ? Globals.errorText(o) : "[" + Globals.errorText(o) + "]";
            }
            String name = constructorName(o);
            String prefix = o.proto == null ? "[Object: null prototype] " : name == null || name.equals("Object") ? "" : name + " ";
            if (depth > 2) {
                return "[" + (o.proto == null ? "Object: null prototype" : name == null ? "Object" : name) + "]";
            }
            return container(o.keys(), o, prefix + "{", "}", depth, prefix + "{}");
        }
        if (v instanceof JsFunction) {
            JsFunction f = (JsFunction) v;
            String head = f.name().isEmpty() ? "[Function (anonymous)]" : "[Function: " + f.name() + "]";
            if (f.isClass()) {
                head = "[class " + (f.name().isEmpty() ? "(anonymous)" : f.name())
                        + (f.parent() != null ? " extends " + f.parent().name() : "") + "]";
            }
            String[] keys = f.hasProps() ? f.props().keys() : new String[0];
            if (keys.length == 0 || depth > 2) {
                return head;
            }
            return container(keys, f.props(), head + " {", "}", depth, head);
        }
        return JS.str(v);
    }

    /** The name of the nearest constructor on the prototype chain, or null if there is none. */
    private static String constructorName(JsObject o) {
        for (JsObject p = o.proto; p != null; p = p.proto) {
            Object c = p.getOwn("constructor");
            if (c instanceof JsFunction) {
                return ((JsFunction) c).name();
            }
        }
        return null;
    }

    /** Formats {@code keys} of {@code o} as {@code key: value} entries inside braces, or {@code empty} if there are none. */
    private static String container(String[] keys, JsObject o, String open, String close, int depth, String empty) {
        JsSymbol[] symbols = o.symbolKeys(false);
        if (keys.length == 0 && symbols.length == 0) {
            return empty;
        }
        lastDepth = depth;
        ArrayList<String> parts = new ArrayList<>();
        Object[] all = new Object[keys.length + symbols.length];
        for (int i = 0; i < keys.length; i++) {
            all[i] = keys[i];
        }
        for (int i = 0; i < symbols.length; i++) {
            all[keys.length + i] = symbols[i];
        }
        for (Object key : all) {
            Object raw = o.getOwnRaw(key);
            String shown;
            if (raw instanceof JsObject.Accessor) {
                JsObject.Accessor a = (JsObject.Accessor) raw;
                shown = a.getter != null && a.setter != null ? "[Getter/Setter]" : a.getter != null ? "[Getter]" : "[Setter]";
            } else {
                shown = format(raw, depth + 1);
            }
            String name = key instanceof JsSymbol ? "[" + key + "]"
                    : isIdentifier((String) key) ? (String) key : quote((String) key);
            parts.add(name + ": " + shown);
        }
        return combine(parts, open, close, depth);
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
