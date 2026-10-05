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

    public static JsFunction fn(String name, JsFunction.Body body) {
        return new JsFunction(name, body);
    }

    public static Object array(Object... elements) {
        return new JsArray(elements);
    }

    /** {@code object("a", 1, "b", 2)} builds {@code {a: 1, b: 2}}. */
    public static Object object(Object... keysAndValues) {
        JsObject o = new JsObject();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            o.set((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return o;
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
        if (v instanceof JsArray) {
            return Builtins.join((JsArray) v, ",");
        }
        if (v instanceof JsFunction) {
            return "function () { [native code] }";
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
        if (v instanceof JsFunction) {
            return "function";
        }
        return "object";
    }

    // ---- operators ----

    private static boolean isObjectLike(Object v) {
        return v instanceof JsArray || v instanceof JsObject || v instanceof JsFunction;
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
            return ((JsObject) o).get(keyString(key));
        }
        if (o instanceof JsArray) {
            JsArray array = (JsArray) o;
            if (key instanceof Long) {
                return array.get((int) ((Long) key).longValue());
            }
            if ("length".equals(key)) {
                return Long.valueOf(array.length());
            }
            return Builtins.method(o, keyString(key));
        }
        if (o instanceof String) {
            String s = (String) o;
            if (key instanceof Long) {
                long i = ((Long) key).longValue();
                return i >= 0 && i < s.length() ? s.substring((int) i, (int) i + 1) : null;
            }
            if ("length".equals(key)) {
                return Long.valueOf(s.length());
            }
            return Builtins.method(o, keyString(key));
        }
        if (o instanceof JsFunction) {
            JsFunction f = (JsFunction) o;
            return f.hasProps() ? f.props().get(keyString(key)) : null;
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
            ((JsObject) o).set(keyString(key), value);
        } else if (o instanceof JsArray) {
            JsArray array = (JsArray) o;
            if (key instanceof Long) {
                long i = ((Long) key).longValue();
                if (i < 0) {
                    throw new JsError("RangeError: negative array indexes are not supported");
                }
                array.set((int) i, value);
            } else if ("length".equals(key)) {
                array.setLength((int) toNumber(value));
            } else {
                throw new JsError("TypeError: arrays cannot have a property named '" + str(key) + "' yet");
            }
        } else if (o instanceof JsFunction) {
            ((JsFunction) o).props().set(keyString(key), value);
        } else if (nullish(o)) {
            throw new JsError("TypeError: Cannot set properties of " + str(o) + " (setting '" + str(key) + "')");
        }
        return value;
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

    /** Calls {@code f} with {@code this} undefined. */
    public static Object call(Object f, Object... args) {
        return callWith(f, null, args);
    }

    /** Calls {@code o[key](...args)} with {@code this} bound to {@code o}. */
    public static Object invoke(Object o, Object key, Object... args) {
        if (key instanceof String && (o instanceof JsArray || o instanceof String)) {
            Object result = Builtins.invoke(o, (String) key, args);
            if (result != Builtins.NO_METHOD) {
                return result;
            }
        }
        return callWith(get(o, key), o, args);
    }

    static Object callWith(Object f, Object self, Object[] args) {
        if (!(f instanceof JsFunction)) {
            throw new JsError("TypeError: " + (f == null ? "undefined" : str(f)) + " is not a function");
        }
        return ((JsFunction) f).call(self, args);
    }
}
