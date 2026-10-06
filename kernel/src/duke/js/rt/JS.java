package duke.js.rt;

/**
 * What generated code calls. Values are plain Java objects: {@code Long} numbers, {@code String},
 * {@code Boolean}, {@link JsArray}, {@link JsObject}, {@link JsFunction}, Java {@code null} for
 * undefined and {@link #NULL} for null.
 *
 * <p>Numbers are 64-bit integers for now, so there is no NaN, Infinity or fraction. Anything that
 * would produce one throws a {@link JsError} instead.
 */
public final class JS {

    /** JavaScript's {@code null}. Java {@code null} is {@code undefined}. */
    public static final Object NULL = new JsNull();

    /** Undefined, as an expression whose static type is Object, so it is never taken as an empty vararg. */
    public static final Object U = null;

    public static final Object ONE = Long.valueOf(1);

    /**
     * Always true. Generated code writes {@code if (JS.T) return x;} so javac never decides the
     * statement after a return, break or continue is unreachable, which JavaScript allows.
     */
    public static boolean T = true;

    private static final class JsNull {
        @Override
        public String toString() {
            return "null";
        }
    }

    private JS() {
    }

    // ---- construction ----

    public static Object n(long value) {
        return Long.valueOf(value);
    }

    public static Object bool(boolean b) {
        return b ? Boolean.TRUE : Boolean.FALSE;
    }

    /** A function declaration or expression, which {@code new} can use. */
    public static JsFunction fn(String name, JsFunction.Body body) {
        return new JsFunction(name, body, true);
    }

    /** An arrow function: no {@code prototype}, and {@code new} is a TypeError. */
    public static JsFunction arrow(String name, JsFunction.Body body) {
        return new JsFunction(name, body, false);
    }

    public static Object array(Object... elements) {
        return new JsArray(elements);
    }

    /** {@code object("a", 1, "b", 2)} builds {@code {a: 1, b: 2}}. */
    public static Object object(Object... keysAndValues) {
        JsObject o = new JsObject();
        o.proto = Globals.objectPrototype();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            o.set(key(keysAndValues[i]), keysAndValues[i + 1]);
        }
        return o;
    }

    /** Marks {@code ...value} among call arguments, array elements and object members. */
    private static final class Spreading {
        final Object value;

        Spreading(Object value) {
            this.value = value;
        }
    }

    public static Object spreadOf(Object value) {
        return new Spreading(value);
    }

    /** The generator object for a generator function's body: {@code resume} is the state machine jsc built. */
    public static Object generator(Object resume) {
        return Globals.generatorObject(new JsGenerator((JsFunction) resume));
    }

    /** What the state machine returns to hand a value out of {@code yield}. */
    public static Object yielded(Object value) {
        return new JsGenerator.Yielded(value);
    }

    /** What {@code yield* iterable} walks: a fast iterator for arrays and strings, else the iterator protocol. */
    private static final class Delegate {
        JsIter fast;
        Object iterator;
    }

    public static Object delegate(Object iterable) {
        Delegate d = new Delegate();
        if (iterable instanceof JsArray || iterable instanceof String || iterable instanceof JsMap) {
            d.fast = iter(iterable);
        } else {
            Object method = nullish(iterable) ? null : get(iterable, Globals.ITERATOR);
            if (!(method instanceof JsFunction)) {
                throw new JsError("TypeError: " + (iterable == null ? "undefined" : str(iterable)) + " is not iterable");
            }
            d.iterator = ((JsFunction) method).call(iterable, new Object[0]);
        }
        return d;
    }

    /** One step of {@code yield*}: forwards next, throw or return (mode 0, 1, 2) to the inner iterator. */
    public static Object delegateStep(Object delegate, Object mode, Object arg) {
        Delegate d = (Delegate) delegate;
        long m = ((Long) mode).longValue();
        if (d.fast != null) {
            if (m == 1) {
                // Array and string iterators have no throw method.
                d.fast.close();
                throw new JsError("TypeError: The iterator does not provide a 'throw' method");
            }
            if (m == 2) {
                d.fast.close();
                return JsGenerator.result(arg, true);
            }
            boolean more = d.fast.next();
            return JsGenerator.result(more ? d.fast.value() : null, !more);
        }
        Object result;
        if (m == 0) {
            result = callWith(get(d.iterator, "next"), d.iterator, new Object[] {arg});
        } else if (m == 1) {
            Object thrower = get(d.iterator, "throw");
            if (!(thrower instanceof JsFunction)) {
                Object closer = get(d.iterator, "return");
                if (closer instanceof JsFunction) {
                    ((JsFunction) closer).call(d.iterator, new Object[0]);
                }
                throw new JsError("TypeError: The iterator does not provide a 'throw' method");
            }
            result = ((JsFunction) thrower).call(d.iterator, new Object[] {arg});
        } else {
            Object returner = get(d.iterator, "return");
            if (!(returner instanceof JsFunction)) {
                return JsGenerator.result(arg, true);
            }
            result = ((JsFunction) returner).call(d.iterator, new Object[] {arg});
        }
        if (!(result instanceof JsObject)) {
            throw new JsError("TypeError: Iterator result " + str(result) + " is not an object");
        }
        return result;
    }

    // The loop machinery a for...of inside a generator is built from.

    public static Object iterNext(Object it) {
        return bool(((JsIter) it).next());
    }

    public static Object iterValue(Object it) {
        return ((JsIter) it).value();
    }

    public static Object iterClose(Object it) {
        ((JsIter) it).close();
        return null;
    }

    public static Object lengthOf(Object o) {
        return Long.valueOf(length(o));
    }

    /** An iterator over {@code iterable}: what {@code for (x of iterable)} loops with. */
    public static JsIter iter(Object iterable) {
        if (iterable instanceof JsArray) {
            return new JsIter.ArrayIter((JsArray) iterable);
        }
        if (iterable instanceof String) {
            return new JsIter.StringIter((String) iterable);
        }
        if (iterable instanceof JsMap && !((JsMap) iterable).weak) {
            return ((JsMap) iterable).iterate(((JsMap) iterable).isSet ? 1 : 2);
        }
        Object method = nullish(iterable) ? null : get(iterable, Globals.ITERATOR);
        if (!(method instanceof JsFunction)) {
            throw new JsError("TypeError: " + (iterable == null ? "undefined" : iterable instanceof JsSymbol
                    ? iterable.toString() : str(iterable)) + " is not iterable");
        }
        Object iterator = ((JsFunction) method).call(iterable, new Object[0]);
        if (!(iterator instanceof JsObject)) {
            throw new JsError("TypeError: Result of the Symbol.iterator method is not an object");
        }
        return new JsIter.ProtocolIter(iterator);
    }

    /** The values of an iterable, appended to {@code out}. */
    static void addAll(JsArray out, Object iterable) {
        JsIter it = iter(iterable);
        try {
            while (it.next()) {
                out.add(it.value());
            }
        } finally {
            it.close();
        }
    }

    /** The arguments or elements for a list that contains spreads: {@code f(a, ...b)} becomes {@code spread(a, spreadOf(b))}. */
    public static Object[] spread(Object... parts) {
        JsArray out = new JsArray();
        for (Object part : parts) {
            if (part instanceof Spreading) {
                addAll(out, ((Spreading) part).value);
            } else {
                out.add(part);
            }
        }
        Object[] result = new Object[out.length()];
        for (int i = 0; i < result.length; i++) {
            result[i] = out.get(i);
        }
        return result;
    }

    /** A getter or setter written in an object literal, among the parts handed to {@link #objectSpread}. */
    private static final class Accessing {
        final Object key;
        final JsFunction function;
        final boolean getter;

        Accessing(Object key, JsFunction function, boolean getter) {
            this.key = key;
            this.function = function;
            this.getter = getter;
        }
    }

    public static Object getter(Object key, Object function) {
        return new Accessing(key, (JsFunction) function, true);
    }

    public static Object setter(Object key, Object function) {
        return new Accessing(key, (JsFunction) function, false);
    }

    /**
     * {@code {a: 1, ...other, b: 2, get c() {...}}}: members in order, each a key and a value, a lone spread of an
     * object, or a lone getter or setter.
     */
    public static Object objectSpread(Object... parts) {
        JsObject o = new JsObject();
        o.proto = Globals.objectPrototype();
        for (int i = 0; i < parts.length; ) {
            if (parts[i] instanceof Spreading) {
                copyOwn(o, ((Spreading) parts[i]).value);
                i++;
            } else if (parts[i] instanceof Accessing) {
                Accessing a = (Accessing) parts[i];
                o.defineAccessor(key(a.key), a.getter ? a.function : null, a.getter ? null : a.function);
                i++;
            } else {
                o.set(key(parts[i]), parts[i + 1]);
                i += 2;
            }
        }
        return o;
    }

    private static void copyOwn(JsObject target, Object source) {
        if (source instanceof JsObject) {
            JsObject from = (JsObject) source;
            for (String key : from.keys()) {
                target.set(key, from.getOwn(key));
            }
        } else if (source instanceof JsArray || source instanceof String) {
            for (long i = 0; i < length(source); i++) {
                target.set(Long.toString(i), getIndex(source, i));
            }
        }
    }

    /** The value to destructure with {@code [a, b] = value}: an array, or the elements of an iterable as one. */
    public static Object toArray(Object value) {
        return toArray(value, Long.valueOf(-1));
    }

    /** Like {@link #toArray(Object)} but takes at most {@code limitValue} values (all if negative), then closes the iterator. */
    public static Object toArray(Object value, Object limitValue) {
        if (value instanceof JsArray) {
            return value;
        }
        long limit = ((Long) limitValue).longValue();
        JsArray out = new JsArray();
        JsIter it = iter(value);
        try {
            while ((limit < 0 || out.length() < limit) && it.next()) {
                out.add(it.value());
            }
        } finally {
            it.close();
        }
        return out;
    }

    /** The value to destructure with {@code {a, b} = value}: anything but null and undefined. */
    public static Object requireObject(Object value) {
        if (nullish(value)) {
            throw new JsError("TypeError: Cannot destructure '" + str(value) + "' as it is " + str(value) + ".");
        }
        return value;
    }

    /** {@code const {a, ...rest} = o}: the own enumerable properties of {@code o} other than {@code excluded}. */
    public static Object objectRest(Object o, Object... excluded) {
        JsObject rest = new JsObject();
        rest.proto = Globals.objectPrototype();
        JsArray keys = (JsArray) forInKeys(o instanceof JsObject ? ownCopy((JsObject) o) : o);
        for (int i = 0; i < keys.length(); i++) {
            String key = (String) keys.get(i);
            boolean skip = false;
            for (Object name : excluded) {
                skip = skip || key.equals(keyString(name));
            }
            if (!skip) {
                rest.set(key, get(o, key));
            }
        }
        return rest;
    }

    private static JsObject ownCopy(JsObject source) {
        JsObject copy = new JsObject();
        for (String key : source.keys()) {
            copy.set(key, source.getOwn(key));
        }
        return copy;
    }

    /** The parameter {@code ...rest}: the arguments from index {@code from} on. */
    public static Object restArgs(Object[] args, int from) {
        JsArray rest = new JsArray();
        for (int i = from; i < args.length; i++) {
            rest.add(args[i]);
        }
        return rest;
    }

    /** The {@code arguments} object. It's an ordinary array here, so it has no link back to the parameters. */
    public static Object arguments(Object[] args) {
        return new JsArray(args);
    }

    /** The strings array a tag function receives, with its {@code raw} counterpart. */
    public static Object templateStrings(String[] cooked, String[] raw) {
        Object[] c = new Object[cooked.length];
        Object[] r = new Object[raw.length];
        for (int i = 0; i < c.length; i++) {
            c[i] = cooked[i];
            r[i] = raw[i];
        }
        JsArray strings = new JsArray(c);
        strings.namedOrCreate().setHidden("raw", new JsArray(r));
        return strings;
    }

    public static Object arg(Object[] args, int i) {
        return i < args.length ? args[i] : null;
    }

    public static Object global(String name) {
        return Globals.lookup(name);
    }

    /** Evaluates both, returns the second: the glue for postfix updates and the comma operator. */
    public static Object second(Object first, Object second) {
        return second;
    }

    // ---- conversions ----

    public static boolean truthy(Object v) {
        if (v == null || v == NULL) {
            return false;
        }
        if (v instanceof Boolean) {
            return ((Boolean) v).booleanValue();
        }
        if (v instanceof Long) {
            return ((Long) v).longValue() != 0;
        }
        if (v instanceof String) {
            return !((String) v).isEmpty();
        }
        return true;
    }

    public static boolean nullish(Object v) {
        return v == null || v == NULL;
    }

    public static String str(Object v) {
        if (v == null) {
            return "undefined";
        }
        if (v instanceof String) {
            return (String) v;
        }
        if (v instanceof Long) {
            return Long.toString(((Long) v).longValue());
        }
        if (v instanceof Boolean) {
            return ((Boolean) v).booleanValue() ? "true" : "false";
        }
        if (v == NULL) {
            return "null";
        }
        if (v instanceof JsSymbol) {
            throw new JsError("TypeError: Cannot convert a Symbol value to a string");
        }
        if (v instanceof JsArray) {
            return Builtins.join((JsArray) v, ",");
        }
        if (v instanceof JsFunction) {
            return "function () { [native code] }";
        }
        if (v instanceof JsMap) {
            return Builtins.mapTag((JsMap) v);
        }
        Object toString = ((JsObject) v).get("toString");
        if (toString instanceof JsFunction) {
            Object text = ((JsFunction) toString).call(v, new Object[0]);
            if (text instanceof String) {
                return (String) text;
            }
        }
        return "[object Object]";
    }

    public static Object concat(Object... parts) {
        StringBuilder sb = new StringBuilder();
        for (Object part : parts) {
            sb.append(str(part));
        }
        return sb.toString();
    }

    public static long toNumber(Object v) {
        if (v instanceof Long) {
            return ((Long) v).longValue();
        }
        if (v instanceof Boolean) {
            return ((Boolean) v).booleanValue() ? 1 : 0;
        }
        if (v == NULL) {
            return 0;
        }
        if (v instanceof String) {
            return parseInteger((String) v, true);
        }
        throw nan("converting " + typeof(v) + " to a number");
    }

    static JsError nan(String what) {
        return new JsError("TypeError: " + what + " would be NaN, which is not supported yet");
    }

    /** Parses an optionally signed decimal integer. Strict wants the whole string; otherwise a prefix will do. */
    static long parseInteger(String s, boolean strict) {
        int n = s.length();
        int i = 0;
        while (i < n && s.charAt(i) == ' ') {
            i++;
        }
        boolean negative = false;
        if (i < n && (s.charAt(i) == '-' || s.charAt(i) == '+')) {
            negative = s.charAt(i) == '-';
            i++;
        }
        int start = i;
        long result = 0;
        while (i < n && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
            result = result * 10 + (s.charAt(i) - '0');
            i++;
        }
        if (i == start) {
            if (strict && i == n) {
                return 0;
            }
            throw nan("converting the string '" + s + "' to a number");
        }
        if (strict) {
            while (i < n && s.charAt(i) == ' ') {
                i++;
            }
            if (i != n) {
                throw nan("converting the string '" + s + "' to a number");
            }
        }
        return negative ? -result : result;
    }

    public static String typeof(Object v) {
        if (v == null) {
            return "undefined";
        }
        if (v instanceof Long) {
            return "number";
        }
        if (v instanceof String) {
            return "string";
        }
        if (v instanceof Boolean) {
            return "boolean";
        }
        if (v instanceof JsSymbol) {
            return "symbol";
        }
        if (v instanceof JsFunction) {
            return "function";
        }
        return "object";
    }

    // ---- operators ----

    private static boolean isObjectLike(Object v) {
        return isObject(v);
    }

    /** True for anything JavaScript calls an object: plain objects, arrays, functions, maps and sets. */
    static boolean isObject(Object v) {
        return v instanceof JsObject || v instanceof JsArray || v instanceof JsFunction || v instanceof JsMap;
    }

    public static Object add(Object a, Object b) {
        if (a instanceof Long && b instanceof Long) {
            return Long.valueOf(((Long) a).longValue() + ((Long) b).longValue());
        }
        if (a instanceof String || b instanceof String || isObjectLike(a) || isObjectLike(b)) {
            return str(a).concat(str(b));
        }
        return Long.valueOf(toNumber(a) + toNumber(b));
    }

    public static Object sub(Object a, Object b) {
        return Long.valueOf(toNumber(a) - toNumber(b));
    }

    public static Object mul(Object a, Object b) {
        return Long.valueOf(toNumber(a) * toNumber(b));
    }

    /** Integer division, truncating. Dividing by zero would be Infinity or NaN, which do not exist yet. */
    public static Object div(Object a, Object b) {
        long divisor = toNumber(b);
        if (divisor == 0) {
            throw new JsError("RangeError: division by zero (there is no Infinity or NaN yet)");
        }
        return Long.valueOf(toNumber(a) / divisor);
    }

    public static Object mod(Object a, Object b) {
        long divisor = toNumber(b);
        if (divisor == 0) {
            throw nan("a remainder by zero");
        }
        return Long.valueOf(toNumber(a) % divisor);
    }

    public static Object pow(Object a, Object b) {
        long base = toNumber(a);
        long exponent = toNumber(b);
        if (exponent < 0) {
            throw nan("raising to a negative power");
        }
        long result = 1;
        for (long i = 0; i < exponent; i++) {
            result *= base;
        }
        return Long.valueOf(result);
    }

    public static Object neg(Object a) {
        return Long.valueOf(-toNumber(a));
    }

    public static Object plus(Object a) {
        return Long.valueOf(toNumber(a));
    }

    // Bitwise operators work on 32 bits, as in JavaScript.

    public static Object band(Object a, Object b) {
        return Long.valueOf((int) toNumber(a) & (int) toNumber(b));
    }

    public static Object bor(Object a, Object b) {
        return Long.valueOf((int) toNumber(a) | (int) toNumber(b));
    }

    public static Object bxor(Object a, Object b) {
        return Long.valueOf((int) toNumber(a) ^ (int) toNumber(b));
    }

    public static Object bnot(Object a) {
        return Long.valueOf(~(int) toNumber(a));
    }

    public static Object shl(Object a, Object b) {
        return Long.valueOf((int) toNumber(a) << ((int) toNumber(b) & 31));
    }

    public static Object shr(Object a, Object b) {
        return Long.valueOf((int) toNumber(a) >> ((int) toNumber(b) & 31));
    }

    public static Object ushr(Object a, Object b) {
        return Long.valueOf(((int) toNumber(a) & 0xFFFFFFFFL) >>> ((int) toNumber(b) & 31));
    }

    /** -1, 0 or 1. Strings compare by code unit, everything else as numbers. */
    private static int compare(Object a, Object b) {
        if (a instanceof String && b instanceof String) {
            int c = ((String) a).compareTo((String) b);
            return c < 0 ? -1 : c > 0 ? 1 : 0;
        }
        long x = toNumber(a);
        long y = toNumber(b);
        return x < y ? -1 : x > y ? 1 : 0;
    }

    public static boolean lt(Object a, Object b) {
        return compare(a, b) < 0;
    }

    public static boolean gt(Object a, Object b) {
        return compare(a, b) > 0;
    }

    public static boolean le(Object a, Object b) {
        return compare(a, b) <= 0;
    }

    public static boolean ge(Object a, Object b) {
        return compare(a, b) >= 0;
    }

    /** {@code ===}. */
    public static boolean seq(Object a, Object b) {
        if (a == b) {
            return true;
        }
        if (a instanceof Long && b instanceof Long) {
            return ((Long) a).longValue() == ((Long) b).longValue();
        }
        if (a instanceof String && b instanceof String) {
            return a.equals(b);
        }
        if (a instanceof Boolean && b instanceof Boolean) {
            return a.equals(b);
        }
        return false;
    }

    /** {@code ==}: null and undefined match each other; otherwise primitives compare as numbers. */
    public static boolean leq(Object a, Object b) {
        if (seq(a, b)) {
            return true;
        }
        boolean aNull = nullish(a);
        boolean bNull = nullish(b);
        if (aNull || bNull) {
            return aNull && bNull;
        }
        if (isObjectLike(a) || isObjectLike(b)) {
            return false;
        }
        return toNumber(a) == toNumber(b);
    }

    // ---- properties and calls ----

    public static Object get(Object o, Object key) {
        if (o instanceof JsObject) {
            return ((JsObject) o).get(key(key));
        }
        if (o instanceof JsArray) {
            JsArray array = (JsArray) o;
            if (indexOf(key) >= 0 || key instanceof Long) {
                return array.get((int) indexOf(key));
            }
            if ("length".equals(key)) {
                return Long.valueOf(array.length());
            }
            if (array.named() != null && array.named().has(key(key))) {
                return array.named().get(key(key));
            }
            return key instanceof JsSymbol ? Builtins.symbolMethod(o, (JsSymbol) key) : Builtins.method(o, keyString(key));
        }
        if (o instanceof String) {
            String s = (String) o;
            if (indexOf(key) >= 0 || key instanceof Long) {
                long i = indexOf(key);
                return i >= 0 && i < s.length() ? s.substring((int) i, (int) i + 1) : null;
            }
            if ("length".equals(key)) {
                return Long.valueOf(s.length());
            }
            return key instanceof JsSymbol ? Builtins.symbolMethod(o, (JsSymbol) key) : Builtins.method(o, keyString(key));
        }
        if (o instanceof JsFunction) {
            return Builtins.functionProperty((JsFunction) o, key(key));
        }
        if (o instanceof JsMap) {
            return Builtins.mapProperty((JsMap) o, key(key));
        }
        if (o instanceof JsSymbol) {
            JsSymbol symbol = (JsSymbol) o;
            if ("description".equals(key)) {
                return symbol.description;
            }
            if ("toString".equals(key)) {
                return new JsFunction("toString", (callee, self, args) -> symbol.toString());
            }
            return null;
        }
        if (o instanceof Long || o instanceof Boolean) {
            return null;
        }
        throw new JsError("TypeError: Cannot read properties of " + str(o) + " (reading '" + str(key) + "')");
    }

    public static Object getIndex(Object o, long i) {
        return get(o, Long.valueOf(i));
    }

    public static Object set(Object o, Object key, Object value) {
        if (o instanceof JsObject) {
            ((JsObject) o).assign(key(key), value);
        } else if (o instanceof JsArray) {
            JsArray array = (JsArray) o;
            if (key instanceof Long && ((Long) key).longValue() < 0) {
                throw new JsError("RangeError: negative array indexes are not supported");
            } else if (indexOf(key) >= 0) {
                array.set((int) indexOf(key), value);
            } else if ("length".equals(key)) {
                array.setLength((int) toNumber(value));
            } else {
                array.namedOrCreate().set(key(key), value);
            }
        } else if (o instanceof JsMap) {
            ((JsMap) o).namedOrCreate().assign(key(key), value);
        } else if (o instanceof JsFunction) {
            JsFunction f = (JsFunction) o;
            if ("prototype".equals(key) && value instanceof JsObject) {
                f.setPrototype((JsObject) value);
            } else {
                f.props().assign(key(key), value);
            }
        } else if (nullish(o)) {
            throw new JsError("TypeError: Cannot set properties of " + str(o) + " (setting '" + str(key) + "')");
        }
        return value;
    }

    /** The array index {@code key} names (a number, or a string like "3"), or -1 if it isn't one. */
    static long indexOf(Object key) {
        if (key instanceof Long) {
            long i = ((Long) key).longValue();
            return i >= 0 ? i : -1;
        }
        if (key instanceof String && JsObject.isIndex((String) key)) {
            return Long.parseLong((String) key);
        }
        return -1;
    }

    /** The property key for {@code key}: a symbol stays one; anything else becomes its string. */
    static Object key(Object key) {
        return key instanceof JsSymbol ? key : keyString(key);
    }

    static String keyString(Object key) {
        return key instanceof String ? (String) key : str(key);
    }

    public static long length(Object o) {
        if (o instanceof JsArray) {
            return ((JsArray) o).length();
        }
        if (o instanceof String) {
            return ((String) o).length();
        }
        throw new JsError("TypeError: " + typeof(o) + " is not iterable");
    }

    /** The exception for {@code throw value}. */
    public static RuntimeException thrown(Object value) {
        return new JsThrow(value);
    }

    /**
     * The JavaScript value a {@code catch} sees for a Java exception: what was thrown, or an Error object for a
     * runtime error (a TypeError from reading a property of undefined, say).
     */
    public static Object caught(Throwable t) {
        if (t instanceof JsThrow) {
            return ((JsThrow) t).value;
        }
        if (t instanceof StackOverflowError) {
            return Globals.makeError("RangeError", "Maximum call stack size exceeded");
        }
        String message = t instanceof JsError ? t.getMessage() : String.valueOf(t);
        int colon = Builtins.indexOf(message, ": ", 0);
        if (colon > 0 && Globals.isErrorType(message.substring(0, colon))) {
            return Globals.makeError(message.substring(0, colon), message.substring(colon + 2));
        }
        return Globals.makeError("Error", message);
    }

    /**
     * The class function for {@code class name extends superclass {...}}: its prototype chain and statics follow the
     * superclass, and {@code ctor} and {@code fields} run for each instance. Members are added by the calls that follow.
     */
    public static Object classDef(String name, Object superclass, boolean hasSuper, JsFunction.Body ctor,
            JsFunction.Body fields) {
        JsObject parentPrototype = Globals.objectPrototype();
        JsFunction parent = null;
        if (hasSuper) {
            if (superclass == NULL) {
                parentPrototype = null;
            } else if (superclass instanceof JsFunction && ((JsFunction) superclass).isConstructible()) {
                parent = (JsFunction) superclass;
                parentPrototype = parent.prototype();
            } else {
                throw new JsError("TypeError: Class extends value " + str(superclass) + " is not a constructor or null");
            }
        }
        JsFunction.Body body = ctor != null ? ctor : (callee, self, args) -> null;
        JsFunction cls = JsFunction.classFunction(name, body, fields, hasSuper, parent);
        JsObject prototype = new JsObject();
        prototype.proto = parentPrototype;
        prototype.setHidden("constructor", cls);
        cls.setPrototype(prototype);
        cls.home = prototype;
        return cls;
    }

    /** Adds a method to a class (static ones to the class itself); class members are not enumerable. */
    public static Object classMethod(Object cls, Object key, Object fn, boolean isStatic) {
        JsFunction c = (JsFunction) cls;
        JsFunction method = (JsFunction) fn;
        method.home = isStatic ? c : c.prototype();
        (isStatic ? c.props() : c.prototype()).setHidden(key(key), method);
        return cls;
    }

    public static Object classAccessor(Object cls, Object key, Object fn, boolean isStatic, boolean getter) {
        JsFunction c = (JsFunction) cls;
        JsFunction accessor = (JsFunction) fn;
        accessor.home = isStatic ? c : c.prototype();
        (isStatic ? c.props() : c.prototype()).defineAccessor(key(key), getter ? accessor : null,
                getter ? null : accessor, false);
        return cls;
    }

    /** Runs the static fields and blocks, in order, with the class as {@code this}. */
    public static Object classStatics(Object cls, JsFunction.Body statics) {
        statics.call((JsFunction) cls, cls, new Object[0]);
        return cls;
    }

    /** {@code super(...args)}: the parent runs its constructor on this object, then this class's fields are set up. */
    public static Object superCall(Object callee, Object self, Object... args) {
        JsFunction cls = (JsFunction) callee;
        JsFunction parent = cls.parent();
        if (parent == null || !(self instanceof JsObject)) {
            throw new JsError("TypeError: Super constructor is not a constructor");
        }
        parent.initialize((JsObject) self, args);
        cls.runFieldInit(self);
        return self;
    }

    /** {@code super.key}: looked up from the prototype of the object the running method was defined on. */
    public static Object superGet(Object callee, Object self, Object key) {
        Object home = ((JsFunction) callee).home;
        Object k = key(key);
        if (home instanceof JsFunction) {
            JsFunction parent = ((JsFunction) home).parent();
            return parent == null ? null : Builtins.functionProperty(parent, k);
        }
        JsObject start = home instanceof JsObject ? ((JsObject) home).proto : null;
        return start == null ? null : start.getFor(k, self);
    }

    /** {@code new f(...args)}. */
    public static Object construct(Object f, Object... args) {
        if (!(f instanceof JsFunction)) {
            throw new JsError("TypeError: " + str(f) + " is not a constructor");
        }
        return ((JsFunction) f).construct(args);
    }

    /** {@code a instanceof f}: is f.prototype on a's prototype chain? */
    public static boolean instanceOf(Object a, Object f) {
        if (!(f instanceof JsFunction)) {
            throw new JsError("TypeError: Right-hand side of 'instanceof' is not callable");
        }
        JsFunction function = (JsFunction) f;
        Object custom = function.hasProps() ? function.props().get(Globals.HAS_INSTANCE) : null;
        if (custom instanceof JsFunction) {
            return truthy(((JsFunction) custom).call(f, new Object[] {a}));
        }
        JsObject target = function.prototype();
        if (a instanceof JsObject) {
            for (JsObject p = ((JsObject) a).proto; p != null; p = p.proto) {
                if (p == target) {
                    return true;
                }
            }
            return false;
        }
        if (a instanceof JsArray) {
            return f == Globals.lookup("Array") || f == Globals.lookup("Object");
        }
        if (a instanceof JsFunction) {
            return f == Globals.lookup("Function") || f == Globals.lookup("Object");
        }
        if (a instanceof JsMap) {
            JsMap m = (JsMap) a;
            return f == Globals.lookup(m.weak ? (m.isSet ? "WeakSet" : "WeakMap") : (m.isSet ? "Set" : "Map"))
                    || f == Globals.lookup("Object");
        }
        return false;
    }

    /** {@code key in o}. */
    public static boolean in(Object key, Object o) {
        if (o instanceof JsObject) {
            return ((JsObject) o).has(key(key));
        }
        if (o instanceof JsArray) {
            JsArray array = (JsArray) o;
            long i = indexOf(key);
            return i >= 0 ? i < array.length() : "length".equals(key);
        }
        if (o instanceof JsFunction) {
            return Builtins.functionProperty((JsFunction) o, key(key)) != null
                    || "name".equals(key) || "prototype".equals(key);
        }
        if (o instanceof JsMap) {
            return Builtins.mapProperty((JsMap) o, key(key)) != null;
        }
        throw new JsError("TypeError: Cannot use 'in' operator to search for '" + str(key) + "' in " + str(o));
    }

    /** {@code delete o[key]}: true unless the property can't be removed. */
    public static Object delete(Object o, Object key) {
        if (o instanceof JsObject) {
            JsObject object = (JsObject) o;
            if (!object.remove(key(key)) && object.hasOwn(key(key))) {
                throw new JsError("TypeError: Cannot delete property '" + keyString(key) + "' of #<Object>");
            }
        } else if (o instanceof JsArray && key instanceof Long) {
            // Arrays have no holes here, so a deleted element reads as undefined.
            JsArray array = (JsArray) o;
            int i = (int) ((Long) key).longValue();
            if (i >= 0 && i < array.length()) {
                array.set(i, null);
            }
        } else if (o instanceof JsFunction) {
            ((JsFunction) o).props().remove(key(key));
        } else if (nullish(o)) {
            throw new JsError("TypeError: Cannot convert undefined or null to object");
        }
        return Boolean.TRUE;
    }

    /** The keys {@code for (k in o)} visits: own enumerable ones, then inherited ones not already seen. */
    public static Object forInKeys(Object o) {
        JsArray out = new JsArray();
        if (o instanceof JsObject) {
            for (JsObject p = (JsObject) o; p != null; p = p.proto) {
                for (String key : p.keys()) {
                    boolean seen = false;
                    for (int i = 0; i < out.length(); i++) {
                        seen = seen || key.equals(out.get(i));
                    }
                    if (!seen) {
                        out.add(key);
                    }
                }
            }
        } else if (o instanceof JsArray || o instanceof String) {
            for (long i = 0; i < length(o); i++) {
                out.add(Long.toString(i));
            }
        } else if (o instanceof JsFunction && ((JsFunction) o).hasProps()) {
            for (String key : ((JsFunction) o).props().keys()) {
                out.add(key);
            }
        }
        return out;
    }

    /** Calls {@code f} with {@code this} undefined. */
    public static Object call(Object f, Object... args) {
        return callWith(f, null, args);
    }

    /** Calls {@code o[key](...args)} with {@code this} bound to {@code o}. */
    public static Object invoke(Object o, Object key, Object... args) {
        if (key instanceof String && (o instanceof JsArray || o instanceof String || o instanceof JsFunction
                || o instanceof JsMap)) {
            Object result = Builtins.invoke(o, (String) key, args);
            if (result != Builtins.NO_METHOD) {
                return result;
            }
        }
        return callWith(get(o, key), o, args);
    }

    /** Calls {@code f} with {@code this} bound to {@code self}: the call in {@code o.f?.()}. */
    public static Object callMethod(Object f, Object self, Object... args) {
        return callWith(f, self, args);
    }

    static Object callWith(Object f, Object self, Object[] args) {
        if (!(f instanceof JsFunction)) {
            throw new JsError("TypeError: " + (f == null ? "undefined" : str(f)) + " is not a function");
        }
        return ((JsFunction) f).call(self, args);
    }
}
