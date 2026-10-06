package duke.js.rt;

/** The methods of arrays and strings. Dispatch is by name, so a call allocates nothing extra. */
final class Builtins {

    /** Returned by {@link #invoke} when the receiver has no such method. */
    static final Object NO_METHOD = new Object();

    private static final String[] ARRAY_METHODS = {"values", "keys", "entries", "push", "pop", "shift", "unshift", "slice", "concat", "join",
        "indexOf", "includes", "reverse", "forEach", "map", "filter", "reduce", "some", "every", "find",
        "findIndex", "sort"};

    private static final String[] STRING_METHODS = {"charAt", "charCodeAt", "indexOf", "includes", "startsWith",
        "endsWith", "slice", "substring", "split", "toUpperCase", "toLowerCase", "trim", "repeat", "padStart",
        "padEnd", "concat"};

    private Builtins() {
    }

    private static boolean contains(String[] names, String name) {
        for (String n : names) {
            if (n.equals(name)) {
                return true;
            }
        }
        return false;
    }

    /** {@code o[symbol]} for arrays and strings: their {@code Symbol.iterator}. */
    static Object symbolMethod(Object o, JsSymbol key) {
        if (key == Globals.ITERATOR) {
            return new JsFunction("[Symbol.iterator]", (callee, self, args) -> iterate(self, 1));
        }
        return o instanceof JsArray && ((JsArray) o).named() != null ? ((JsArray) o).named().get(key) : null;
    }

    /** An iterator object over an array (or string) of keys (0), values (1) or [key, value] pairs (2). */
    private static Object iterate(Object o, int kind) {
        if (o instanceof String) {
            return Globals.iteratorObject(new JsIter.StringIter((String) o));
        }
        JsArray a = (JsArray) o;
        return Globals.iteratorObject(new JsIter() {
            private int index = -1;

            @Override
            public boolean next() {
                return ++index < a.length();
            }

            @Override
            public Object value() {
                if (kind == 0) {
                    return Long.valueOf(index);
                }
                return kind == 1 ? a.get(index) : new JsArray(new Object[] {Long.valueOf(index), a.get(index)});
            }
        });
    }

    /** {@code o.name} for a method: a function bound to nothing; its caller supplies {@code this}. */
    static Object method(Object o, String name) {
        boolean known = o instanceof JsArray ? contains(ARRAY_METHODS, name) : contains(STRING_METHODS, name);
        if (!known) {
            return null;
        }
        return new JsFunction(name, (callee, self, args) -> invoke(self, name, args));
    }

    static Object invoke(Object o, String name, Object[] args) {
        if (o instanceof JsFunction) {
            return functionMethod((JsFunction) o, name, args);
        }
        if (o instanceof JsArray) {
            return contains(ARRAY_METHODS, name) ? arrayMethod((JsArray) o, name, args) : NO_METHOD;
        }
        return contains(STRING_METHODS, name) ? stringMethod((String) o, name, args) : NO_METHOD;
    }

    // ---- functions ----

    /** {@code f.key}: the built-in properties, then whatever the program stored on the function. */
    static Object functionProperty(JsFunction f, Object key) {
        if (key instanceof String) {
            String name = (String) key;
            if (name.equals("prototype")) {
                return f.prototype();
            }
            if (name.equals("name")) {
                return f.name();
            }
            if (name.equals("call") || name.equals("apply") || name.equals("bind")) {
                return new JsFunction(name, (callee, self, args) -> functionMethod((JsFunction) self, name, args));
            }
        }
        if (f.hasProps() && f.props().has(key)) {
            return f.props().getFor(key, f);
        }
        return f.parent() == null ? null : functionProperty(f.parent(), key);
    }

    /** {@code f.call(this, ...)}, {@code f.apply(this, array)} and {@code f.bind(this, ...)}. */
    private static Object functionMethod(JsFunction f, String name, Object[] args) {
        switch (name) {
            case "call":
                return f.call(arg(args, 0), rest(args, 1));
            case "apply": {
                Object list = arg(args, 1);
                Object[] spread = new Object[0];
                if (list instanceof JsArray) {
                    JsArray a = (JsArray) list;
                    spread = new Object[a.length()];
                    for (int i = 0; i < spread.length; i++) {
                        spread[i] = a.get(i);
                    }
                } else if (!JS.nullish(list)) {
                    throw new JsError("TypeError: CreateListFromArrayLike called on non-object");
                }
                return f.call(arg(args, 0), spread);
            }
            case "bind": {
                Object boundThis = arg(args, 0);
                Object[] boundArgs = rest(args, 1);
                return new JsFunction(f.name(), (callee, self, later) -> {
                    Object[] all = new Object[boundArgs.length + later.length];
                    for (int i = 0; i < boundArgs.length; i++) {
                        all[i] = boundArgs[i];
                    }
                    for (int i = 0; i < later.length; i++) {
                        all[boundArgs.length + i] = later[i];
                    }
                    return f.call(boundThis, all);
                });
            }
            default:
                return NO_METHOD;
        }
    }

    private static Object[] rest(Object[] args, int from) {
        Object[] out = new Object[args.length > from ? args.length - from : 0];
        for (int i = 0; i < out.length; i++) {
            out[i] = args[from + i];
        }
        return out;
    }

    // ---- helpers ----

    private static Object arg(Object[] args, int i) {
        return JS.arg(args, i);
    }

    private static Object num(long n) {
        return Long.valueOf(n);
    }

    private static Object callback(Object f, Object... callArgs) {
        return JS.callWith(f, null, callArgs);
    }

    /** Resolves a relative index (negative counts from the end) and clamps it to [0, n]. */
    private static int index(Object value, int n, int dflt) {
        if (value == null) {
            return dflt;
        }
        long i = JS.toNumber(value);
        if (i < 0) {
            i += n;
        }
        return (int) (i < 0 ? 0 : i > n ? n : i);
    }

    static String join(JsArray a, String separator) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length(); i++) {
            if (i > 0) {
                sb.append(separator);
            }
            Object e = a.get(i);
            if (!JS.nullish(e)) {
                sb.append(JS.str(e));
            }
        }
        return sb.toString();
    }

    // ---- arrays ----

    private static Object arrayMethod(JsArray a, String name, Object[] args) {
        int n = a.length();
        switch (name) {
            case "values":
                return iterate(a, 1);
            case "keys":
                return iterate(a, 0);
            case "entries":
                return iterate(a, 2);
            case "push":
                for (Object v : args) {
                    a.add(v);
                }
                return num(a.length());
            case "pop":
                return a.removeLast();
            case "shift":
                return a.removeFirst();
            case "unshift":
                for (int i = args.length - 1; i >= 0; i--) {
                    a.addFirst(args[i]);
                }
                return num(a.length());
            case "slice": {
                JsArray r = new JsArray();
                int to = index(arg(args, 1), n, n);
                for (int i = index(arg(args, 0), n, 0); i < to; i++) {
                    r.add(a.get(i));
                }
                return r;
            }
            case "concat": {
                JsArray r = new JsArray();
                for (int i = 0; i < n; i++) {
                    r.add(a.get(i));
                }
                for (Object v : args) {
                    if (v instanceof JsArray) {
                        JsArray other = (JsArray) v;
                        for (int i = 0; i < other.length(); i++) {
                            r.add(other.get(i));
                        }
                    } else {
                        r.add(v);
                    }
                }
                return r;
            }
            case "join":
                return join(a, arg(args, 0) == null ? "," : JS.str(args[0]));
            case "indexOf":
                for (int i = 0; i < n; i++) {
                    if (JS.seq(a.get(i), arg(args, 0))) {
                        return num(i);
                    }
                }
                return num(-1);
            case "includes":
                for (int i = 0; i < n; i++) {
                    if (JS.seq(a.get(i), arg(args, 0))) {
                        return Boolean.TRUE;
                    }
                }
                return Boolean.FALSE;
            case "reverse":
                for (int i = 0; i < n - 1 - i; i++) {
                    Object t = a.get(i);
                    a.set(i, a.get(n - 1 - i));
                    a.set(n - 1 - i, t);
                }
                return a;
            case "forEach":
                for (int i = 0; i < n; i++) {
                    callback(arg(args, 0), a.get(i), num(i), a);
                }
                return null;
            case "map": {
                JsArray r = new JsArray();
                for (int i = 0; i < n; i++) {
                    r.add(callback(arg(args, 0), a.get(i), num(i), a));
                }
                return r;
            }
            case "filter": {
                JsArray r = new JsArray();
                for (int i = 0; i < n; i++) {
                    if (JS.truthy(callback(arg(args, 0), a.get(i), num(i), a))) {
                        r.add(a.get(i));
                    }
                }
                return r;
            }
            case "reduce": {
                int i = 0;
                Object acc = arg(args, 1);
                if (args.length < 2) {
                    if (n == 0) {
                        throw new JsError("TypeError: Reduce of empty array with no initial value");
                    }
                    acc = a.get(0);
                    i = 1;
                }
                for (; i < n; i++) {
                    acc = callback(args[0], acc, a.get(i), num(i), a);
                }
                return acc;
            }
            case "some":
                for (int i = 0; i < n; i++) {
                    if (JS.truthy(callback(arg(args, 0), a.get(i), num(i), a))) {
                        return Boolean.TRUE;
                    }
                }
                return Boolean.FALSE;
            case "every":
                for (int i = 0; i < n; i++) {
                    if (!JS.truthy(callback(arg(args, 0), a.get(i), num(i), a))) {
                        return Boolean.FALSE;
                    }
                }
                return Boolean.TRUE;
            case "find":
                for (int i = 0; i < n; i++) {
                    if (JS.truthy(callback(arg(args, 0), a.get(i), num(i), a))) {
                        return a.get(i);
                    }
                }
                return null;
            case "findIndex":
                for (int i = 0; i < n; i++) {
                    if (JS.truthy(callback(arg(args, 0), a.get(i), num(i), a))) {
                        return num(i);
                    }
                }
                return num(-1);
            default: // sort
                return sort(a, arg(args, 0));
        }
    }

    /** Stable insertion sort. Without a comparator elements compare as strings, like JavaScript. */
    private static Object sort(JsArray a, Object comparator) {
        int n = a.length();
        for (int i = 1; i < n; i++) {
            Object x = a.get(i);
            int j = i - 1;
            while (j >= 0) {
                Object y = a.get(j);
                boolean after = comparator == null
                        ? JS.str(y).compareTo(JS.str(x)) > 0
                        : JS.toNumber(callback(comparator, y, x)) > 0;
                if (!after) {
                    break;
                }
                a.set(j + 1, y);
                j--;
            }
            a.set(j + 1, x);
        }
        return a;
    }

    // ---- strings ----

    static int indexOf(String s, String t, int from) {
        for (int i = from < 0 ? 0 : from; i + t.length() <= s.length(); i++) {
            int j = 0;
            while (j < t.length() && s.charAt(i + j) == t.charAt(j)) {
                j++;
            }
            if (j == t.length()) {
                return i;
            }
        }
        return -1;
    }

    private static String mapLetters(String s, char lo, char hi, int delta) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(c >= lo && c <= hi ? (char) (c + delta) : c);
        }
        return sb.toString();
    }

    private static String pad(String s, Object width, Object fill, boolean atStart) {
        int target = (int) JS.toNumber(width);
        String filler = fill == null ? " " : JS.str(fill);
        if (target <= s.length() || filler.isEmpty()) {
            return s;
        }
        StringBuilder padding = new StringBuilder();
        while (padding.length() < target - s.length()) {
            padding.append(filler);
        }
        padding.setLength(target - s.length());
        return atStart ? padding + s : s + padding;
    }

    private static Object stringMethod(String s, String name, Object[] args) {
        int n = s.length();
        switch (name) {
            case "charAt": {
                long i = arg(args, 0) == null ? 0 : JS.toNumber(args[0]);
                return i < 0 || i >= n ? "" : s.substring((int) i, (int) i + 1);
            }
            case "charCodeAt": {
                long i = arg(args, 0) == null ? 0 : JS.toNumber(args[0]);
                if (i < 0 || i >= n) {
                    throw JS.nan("charCodeAt out of range");
                }
                return num(s.charAt((int) i));
            }
            case "indexOf":
                return num(indexOf(s, JS.str(arg(args, 0)), arg(args, 1) == null ? 0 : (int) JS.toNumber(args[1])));
            case "includes":
                return JS.bool(indexOf(s, JS.str(arg(args, 0)), 0) >= 0);
            case "startsWith":
                return JS.bool(s.startsWith(JS.str(arg(args, 0))));
            case "endsWith":
                return JS.bool(s.endsWith(JS.str(arg(args, 0))));
            case "slice": {
                int from = index(arg(args, 0), n, 0);
                int to = index(arg(args, 1), n, n);
                return from < to ? s.substring(from, to) : "";
            }
            case "substring": {
                int a = arg(args, 0) == null ? 0 : clamp(JS.toNumber(args[0]), n);
                int b = arg(args, 1) == null ? n : clamp(JS.toNumber(args[1]), n);
                return a <= b ? s.substring(a, b) : s.substring(b, a);
            }
            case "split":
                return split(s, arg(args, 0));
            case "toUpperCase":
                return mapLetters(s, 'a', 'z', -32);
            case "toLowerCase":
                return mapLetters(s, 'A', 'Z', 32);
            case "trim":
                return s.trim();
            case "repeat": {
                StringBuilder sb = new StringBuilder();
                for (long i = 0; i < JS.toNumber(arg(args, 0)); i++) {
                    sb.append(s);
                }
                return sb.toString();
            }
            case "padStart":
                return pad(s, arg(args, 0), arg(args, 1), true);
            case "padEnd":
                return pad(s, arg(args, 0), arg(args, 1), false);
            default: // concat
                return s.concat(JS.str(arg(args, 0)));
        }
    }

    private static int clamp(long i, int n) {
        return (int) (i < 0 ? 0 : i > n ? n : i);
    }

    private static Object split(String s, Object separator) {
        JsArray r = new JsArray();
        if (separator == null) {
            r.add(s);
            return r;
        }
        String sep = JS.str(separator);
        if (sep.isEmpty()) {
            for (int i = 0; i < s.length(); i++) {
                r.add(s.substring(i, i + 1));
            }
            return r;
        }
        int from = 0;
        while (true) {
            int at = indexOf(s, sep, from);
            if (at < 0) {
                break;
            }
            r.add(s.substring(from, at));
            from = at + sep.length();
        }
        r.add(s.substring(from));
        return r;
    }
}
