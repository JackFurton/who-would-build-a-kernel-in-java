package duke.js.rt;

/** The methods of arrays and strings. Dispatch is by name, so a call allocates nothing extra. */
final class Builtins {

    /** Returned by {@link #invoke} when the receiver has no such method. */
    static final Object NO_METHOD = new Object();

    private static final String[] ARRAY_METHODS = {"values", "keys", "entries", "push", "pop", "shift", "unshift", "slice", "concat", "join",
        "indexOf", "includes", "reverse", "forEach", "map", "filter", "reduce", "some", "every", "find",
        "findIndex", "sort", "splice", "fill", "flat", "flatMap", "at", "lastIndexOf", "reduceRight", "findLast",
        "findLastIndex", "copyWithin", "toSorted", "toReversed", "toSpliced", "with"};

    private static final String[] STRING_METHODS = {"charAt", "charCodeAt", "indexOf", "includes", "startsWith",
        "endsWith", "slice", "substring", "split", "toUpperCase", "toLowerCase", "trim", "repeat", "padStart",
        "padEnd", "concat", "at", "lastIndexOf", "trimStart", "trimEnd", "trimLeft", "trimRight", "replace", "replaceAll",
        "substr", "localeCompare", "normalize", "toLocaleUpperCase", "toLocaleLowerCase", "codePointAt"};

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

    // ---- Map, Set, WeakMap and WeakSet ----

    static String mapTag(JsMap m) {
        return m.weak ? (m.isSet ? "[object WeakSet]" : "[object WeakMap]") : m.isSet ? "[object Set]" : "[object Map]";
    }

    /** {@code collection.key}: {@code size}, a method, the iterator symbol, or a property the program added. */
    static Object mapProperty(JsMap m, Object key) {
        if (key instanceof JsSymbol) {
            if (key == Globals.ITERATOR && !m.weak) {
                return new JsFunction("[Symbol.iterator]", (callee, self, args) ->
                        Globals.iteratorObject(((JsMap) self).iterate(((JsMap) self).isSet ? 1 : 2)));
            }
            return m.named() == null ? null : m.named().get(key);
        }
        String name = (String) key;
        if (name.equals("size") && !m.weak) {
            return Long.valueOf(m.size());
        }
        if (m.named() != null && m.named().has(name)) {
            return m.named().get(name);
        }
        if (isMapMethod(m, name)) {
            return new JsFunction(name, (callee, self, args) -> mapMethod((JsMap) self, name, args));
        }
        return null;
    }

    private static boolean isMapMethod(JsMap m, String name) {
        if (m.weak) {
            return m.isSet ? name.equals("add") || name.equals("has") || name.equals("delete")
                    : name.equals("get") || name.equals("set") || name.equals("has") || name.equals("delete");
        }
        if (name.equals("has") || name.equals("delete") || name.equals("clear") || name.equals("forEach")
                || name.equals("keys") || name.equals("values") || name.equals("entries")) {
            return true;
        }
        return m.isSet ? name.equals("add") : name.equals("get") || name.equals("set");
    }

    private static void checkWeakKey(JsMap m, Object key) {
        if (m.weak && !(JS.isObject(key) || key instanceof JsSymbol)) {
            throw new JsError("TypeError: Invalid value used " + (m.isSet ? "in weak set" : "as weak map key"));
        }
    }

    private static Object mapMethod(JsMap m, String name, Object[] args) {
        if (!isMapMethod(m, name)) {
            return NO_METHOD;
        }
        Object key = arg(args, 0);
        switch (name) {
            case "get":
                return m.get(key);
            case "set":
                checkWeakKey(m, key);
                m.put(key, arg(args, 1));
                return m;
            case "add":
                checkWeakKey(m, key);
                m.put(key, key);
                return m;
            case "has":
                return JS.bool(m.has(key));
            case "delete":
                return JS.bool(m.delete(key));
            case "clear":
                m.clear();
                return null;
            case "forEach": {
                m.beginIteration();
                try {
                    for (int i = 0; i < m.limit(); i++) {
                        if (m.liveAt(i)) {
                            Object k = m.keyAt(i);
                            callback(arg(args, 0), m.isSet ? k : m.valueAt(i), k, m);
                        }
                    }
                } finally {
                    m.endIteration();
                }
                return null;
            }
            case "keys":
                return Globals.iteratorObject(m.iterate(0));
            case "values":
                return Globals.iteratorObject(m.iterate(1));
            default: // entries
                return Globals.iteratorObject(m.iterate(2));
        }
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
        if (o instanceof JsMap) {
            return mapMethod((JsMap) o, name, args);
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
                for (int i = index(arg(args, 1), n, 0); i < n; i++) {
                    if (JS.seq(a.get(i), arg(args, 0))) {
                        return num(i);
                    }
                }
                return num(-1);
            case "includes":
                for (int i = index(arg(args, 1), n, 0); i < n; i++) {
                    if (JS.seq(a.get(i), arg(args, 0))) {
                        return Boolean.TRUE;
                    }
                }
                return Boolean.FALSE;
            case "lastIndexOf":
                for (int i = index(arg(args, 1), n, n - 1) >= n ? n - 1 : index(arg(args, 1), n, n - 1); i >= 0; i--) {
                    if (JS.seq(a.get(i), arg(args, 0))) {
                        return num(i);
                    }
                }
                return num(-1);
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
            case "splice": {
                int from = index(arg(args, 0), n, 0);
                int count = args.length == 0 ? 0 : args.length == 1 ? n - from : clamp(JS.toNumber(args[1]), n - from);
                return a.replace(from, count, rest(args, 2));
            }
            case "fill": {
                int to = index(arg(args, 2), n, n);
                for (int i = index(arg(args, 1), n, 0); i < to; i++) {
                    a.set(i, arg(args, 0));
                }
                return a;
            }
            case "flat": {
                JsArray r = new JsArray();
                flatten(a, arg(args, 0) == null ? 1 : JS.toNumber(args[0]), r);
                return r;
            }
            case "flatMap": {
                JsArray r = new JsArray();
                for (int i = 0; i < n; i++) {
                    Object mapped = callback(arg(args, 0), a.get(i), num(i), a);
                    if (mapped instanceof JsArray) {
                        flatten((JsArray) mapped, 0, r);
                    } else {
                        r.add(mapped);
                    }
                }
                return r;
            }
            case "at": {
                long i = JS.toNumber(arg(args, 0));
                return a.get((int) (i < 0 ? i + n : i));
            }
            case "reduceRight": {
                int i = n - 1;
                Object acc = arg(args, 1);
                if (args.length < 2) {
                    if (n == 0) {
                        throw new JsError("TypeError: Reduce of empty array with no initial value");
                    }
                    acc = a.get(i--);
                }
                for (; i >= 0; i--) {
                    acc = callback(args[0], acc, a.get(i), num(i), a);
                }
                return acc;
            }
            case "findLast":
                for (int i = n - 1; i >= 0; i--) {
                    if (JS.truthy(callback(arg(args, 0), a.get(i), num(i), a))) {
                        return a.get(i);
                    }
                }
                return null;
            case "findLastIndex":
                for (int i = n - 1; i >= 0; i--) {
                    if (JS.truthy(callback(arg(args, 0), a.get(i), num(i), a))) {
                        return num(i);
                    }
                }
                return num(-1);
            case "copyWithin": {
                int target = index(arg(args, 0), n, 0);
                int from = index(arg(args, 1), n, 0);
                int to = index(arg(args, 2), n, n);
                Object[] copy = new Object[Math.max(0, to - from)];
                for (int i = 0; i < copy.length; i++) {
                    copy[i] = a.get(from + i);
                }
                for (int i = 0; i < copy.length && target + i < n; i++) {
                    a.set(target + i, copy[i]);
                }
                return a;
            }
            case "toSorted":
                return sort(copyOf(a), arg(args, 0));
            case "toReversed": {
                JsArray r = new JsArray();
                for (int i = n - 1; i >= 0; i--) {
                    r.add(a.get(i));
                }
                return r;
            }
            case "toSpliced": {
                JsArray r = copyOf(a);
                int from = index(arg(args, 0), n, 0);
                int count = args.length == 0 ? 0 : args.length == 1 ? n - from : clamp(JS.toNumber(args[1]), n - from);
                r.replace(from, count, rest(args, 2));
                return r;
            }
            case "with": {
                long i = JS.toNumber(arg(args, 0));
                if (i < 0) {
                    i += n;
                }
                if (i < 0 || i >= n) {
                    throw new JsError("RangeError: Invalid index : " + JS.str(arg(args, 0)));
                }
                JsArray r = copyOf(a);
                r.set((int) i, arg(args, 1));
                return r;
            }
            default: // sort
                return sort(a, arg(args, 0));
        }
    }

    private static JsArray copyOf(JsArray a) {
        JsArray r = new JsArray();
        for (int i = 0; i < a.length(); i++) {
            r.add(a.get(i));
        }
        return r;
    }

    private static void flatten(JsArray a, long depth, JsArray out) {
        for (int i = 0; i < a.length(); i++) {
            Object v = a.get(i);
            if (v instanceof JsArray && depth > 0) {
                flatten((JsArray) v, depth - 1, out);
            } else {
                out.add(v);
            }
        }
    }

    /** Stable merge sort. Without a comparator elements compare as strings, like JavaScript. */
    private static Object sort(JsArray a, Object comparator) {
        int n = a.length();
        Object[] items = new Object[n];
        for (int i = 0; i < n; i++) {
            items[i] = a.get(i);
        }
        Object[] scratch = new Object[n];
        mergeSort(items, scratch, 0, n, comparator);
        for (int i = 0; i < n; i++) {
            a.set(i, items[i]);
        }
        return a;
    }

    private static boolean after(Object x, Object y, Object comparator) {
        return comparator == null ? JS.str(x).compareTo(JS.str(y)) > 0 : JS.toNumber(callback(comparator, x, y)) > 0;
    }

    private static void mergeSort(Object[] items, Object[] scratch, int from, int to, Object comparator) {
        if (to - from < 2) {
            return;
        }
        int middle = (from + to) / 2;
        mergeSort(items, scratch, from, middle, comparator);
        mergeSort(items, scratch, middle, to, comparator);
        int left = from;
        int right = middle;
        for (int i = from; i < to; i++) {
            if (left < middle && (right >= to || !after(items[left], items[right], comparator))) {
                scratch[i] = items[left++];
            } else {
                scratch[i] = items[right++];
            }
        }
        for (int i = from; i < to; i++) {
            items[i] = scratch[i];
        }
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
                return JS.bool(indexOf(s, JS.str(arg(args, 0)), arg(args, 1) == null ? 0 : (int) JS.toNumber(args[1])) >= 0);
            case "startsWith": {
                String prefix = JS.str(arg(args, 0));
                int at = arg(args, 1) == null ? 0 : clamp(JS.toNumber(args[1]), n);
                return JS.bool(s.substring(at).startsWith(prefix));
            }
            case "endsWith": {
                String suffix = JS.str(arg(args, 0));
                int end = arg(args, 1) == null ? n : clamp(JS.toNumber(args[1]), n);
                return JS.bool(s.substring(0, end).endsWith(suffix));
            }
            case "at": {
                long i = JS.toNumber(arg(args, 0));
                i = i < 0 ? i + n : i;
                return i < 0 || i >= n ? null : s.substring((int) i, (int) i + 1);
            }
            case "lastIndexOf": {
                String needle = JS.str(arg(args, 0));
                for (int i = Math.min(n - needle.length(), arg(args, 1) == null ? n : clamp(JS.toNumber(args[1]), n)); i >= 0; i--) {
                    if (indexOf(s.substring(i), needle, 0) == 0) {
                        return num(i);
                    }
                }
                return num(-1);
            }
            case "trimStart":
            case "trimLeft": {
                int i = 0;
                while (i < n && s.charAt(i) <= ' ') {
                    i++;
                }
                return s.substring(i);
            }
            case "trimEnd":
            case "trimRight": {
                int end = n;
                while (end > 0 && s.charAt(end - 1) <= ' ') {
                    end--;
                }
                return s.substring(0, end);
            }
            case "replace":
                return replace(s, JS.str(arg(args, 0)), arg(args, 1), false);
            case "replaceAll":
                return replace(s, JS.str(arg(args, 0)), arg(args, 1), true);
            case "substr": {
                int from = index(arg(args, 0), n, 0);
                int count = arg(args, 1) == null ? n - from : clamp(JS.toNumber(args[1]), n - from);
                return s.substring(from, from + count);
            }
            case "localeCompare": {
                int c = s.compareTo(JS.str(arg(args, 0)));
                return num(c < 0 ? -1 : c > 0 ? 1 : 0);
            }
            case "normalize":
                return s;
            case "toLocaleUpperCase":
                return mapLetters(s, 'a', 'z', -32);
            case "toLocaleLowerCase":
                return mapLetters(s, 'A', 'Z', 32);
            case "codePointAt": {
                long i = arg(args, 0) == null ? 0 : JS.toNumber(args[0]);
                return i < 0 || i >= n ? null : num(s.charAt((int) i));
            }
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
            case "split": {
                JsArray parts = (JsArray) split(s, arg(args, 0));
                if (arg(args, 1) != null && JS.toNumber(args[1]) < parts.length()) {
                    parts.setLength((int) JS.toNumber(args[1]));
                }
                return parts;
            }
            case "toUpperCase":
                return mapLetters(s, 'a', 'z', -32);
            case "toLowerCase":
                return mapLetters(s, 'A', 'Z', 32);
            case "trim":
                return s.trim();
            case "repeat": {
                if (JS.toNumber(arg(args, 0)) < 0) {
                    throw new JsError("RangeError: Invalid count value: " + JS.str(arg(args, 0)));
                }
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
            default: { // concat
                StringBuilder all = new StringBuilder(s);
                for (Object part : args) {
                    all.append(JS.str(part));
                }
                return all.toString();
            }
        }
    }

    /** {@code s.replace(search, replacement)} for a plain string pattern; the replacement may be a function. */
    private static String replace(String s, String search, Object replacement, boolean all) {
        StringBuilder out = new StringBuilder();
        int from = 0;
        int at = indexOf(s, search, 0);
        while (at >= 0 && at <= s.length()) {
            out.append(s.substring(from, at));
            out.append(replacementText(search, at, s, replacement));
            if (search.isEmpty()) {
                // An empty pattern matches between every character, so step over one to move on.
                if (at < s.length()) {
                    out.append(s.charAt(at));
                }
                from = at + 1;
            } else {
                from = at + search.length();
            }
            if (!all) {
                break;
            }
            at = indexOf(s, search, from);
            if (search.isEmpty() && from > s.length()) {
                at = -1;
            }
        }
        if (from <= s.length()) {
            out.append(s.substring(from));
        }
        return out.toString();
    }

    private static String replacementText(String match, int at, String s, Object replacement) {
        if (replacement instanceof JsFunction) {
            return JS.str(callback(replacement, match, num(at), s));
        }
        String text = JS.str(replacement);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '$' && i + 1 < text.length() && text.charAt(i + 1) == '$') {
                out.append('$');
                i++;
            } else if (c == '$' && i + 1 < text.length() && text.charAt(i + 1) == '&') {
                out.append(match);
                i++;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    // ---- numbers and booleans ----

    /** {@code (5).toString(2)} and friends: the methods a number or boolean value has. */
    static Object primitiveMethod(Object o, Object key) {
        if (!(key instanceof String)) {
            return null;
        }
        String name = (String) key;
        boolean known = o instanceof Long ? name.equals("toString") || name.equals("toFixed") || name.equals("valueOf")
                || name.equals("toLocaleString") : name.equals("toString") || name.equals("valueOf");
        return known ? new JsFunction(name, (callee, self, args) -> invokePrimitive(self, name, args)) : null;
    }

    static Object invokePrimitive(Object o, String name, Object[] args) {
        if (o instanceof Long) {
            long n = ((Long) o).longValue();
            switch (name) {
                case "toString":
                    return radixString(n, arg(args, 0) == null ? 10 : (int) JS.toNumber(args[0]));
                case "toFixed": {
                    long digits = arg(args, 0) == null ? 0 : JS.toNumber(args[0]);
                    StringBuilder sb = new StringBuilder(Long.toString(n));
                    if (digits > 0) {
                        sb.append('.');
                        for (long i = 0; i < digits; i++) {
                            sb.append('0');
                        }
                    }
                    return sb.toString();
                }
                case "toLocaleString": {
                    String digits = Long.toString(n < 0 ? -n : n);
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < digits.length(); i++) {
                        if (i > 0 && (digits.length() - i) % 3 == 0) {
                            sb.append(',');
                        }
                        sb.append(digits.charAt(i));
                    }
                    return n < 0 ? "-" + sb : sb.toString();
                }
                case "valueOf":
                    return o;
                default:
                    return NO_METHOD;
            }
        }
        return name.equals("toString") ? JS.str(o) : name.equals("valueOf") ? o : NO_METHOD;
    }

    private static String radixString(long n, int radix) {
        if (radix < 2 || radix > 36) {
            throw new JsError("RangeError: toString() radix must be between 2 and 36");
        }
        if (n == 0) {
            return "0";
        }
        boolean negative = n < 0;
        StringBuilder sb = new StringBuilder();
        long rest = n;
        while (rest != 0) {
            long digit = rest % radix;
            sb.append("0123456789abcdefghijklmnopqrstuvwxyz".charAt((int) (digit < 0 ? -digit : digit)));
            rest /= radix;
        }
        if (negative) {
            sb.append('-');
        }
        return sb.reverse().toString();
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
