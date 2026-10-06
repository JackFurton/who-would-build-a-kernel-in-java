package duke.js.rt;

/** The global names every program sees: console, Math, String and friends. The kernel adds {@code Kernel}. */
public final class Globals {

    /** Where console.log writes. The kernel points it at the console; tests point it at stdout. */
    public interface Sink {
        void print(String s);
    }

    private static Sink sink;
    private static JsObject table;
    private static JsObject objectPrototype;
    private static final String[] ERROR_TYPES = {"Error", "TypeError", "RangeError", "ReferenceError", "SyntaxError",
        "EvalError", "URIError"};

    private Globals() {
    }

    public static void setSink(Sink s) {
        sink = s;
    }

    static void print(String s) {
        if (sink != null) {
            sink.print(s);
        }
    }

    public static void define(String name, Object value) {
        table().set(name, value);
    }

    public static Object lookup(String name) {
        JsObject t = table();
        if (!t.has(name)) {
            throw new JsError("ReferenceError: " + name + " is not defined");
        }
        return t.get(name);
    }

    /** A function backed by Java code. */
    public static JsFunction function(String name, JsFunction.Body body) {
        return new JsFunction(name, body);
    }

    /** A built-in that {@code new} can use. Its body gets the fresh object as {@code self}. */
    public static JsFunction constructor(String name, JsFunction.Body body) {
        return new JsFunction(name, body, true);
    }

    /** {@code Object.prototype}, which every plain object inherits from. */
    static JsObject objectPrototype() {
        table();
        return objectPrototype;
    }

    private static JsObject table() {
        if (table == null) {
            table = new JsObject();
            install(table);
        }
        return table;
    }

    private static Object arg(Object[] args, int i) {
        return JS.arg(args, i);
    }

    private static void install(JsObject g) {
        JsObject console = new JsObject();
        console.set("log", function("log", (callee, self, args) -> {
            print(Inspect.line(args) + "\n");
            return null;
        }));
        console.set("error", console.get("log"));
        g.set("console", console);

        JsObject math = new JsObject();
        Object identity = function("floor", (callee, self, args) -> arg(args, 0));
        math.set("floor", identity);
        math.set("ceil", identity);
        math.set("round", identity);
        math.set("trunc", identity);
        math.set("abs", function("abs", (callee, self, args) -> {
            long x = JS.toNumber(arg(args, 0));
            return Long.valueOf(x < 0 ? -x : x);
        }));
        math.set("sign", function("sign", (callee, self, args) -> {
            long x = JS.toNumber(arg(args, 0));
            return Long.valueOf(x < 0 ? -1 : x > 0 ? 1 : 0);
        }));
        math.set("pow", function("pow", (callee, self, args) -> JS.pow(arg(args, 0), arg(args, 1))));
        math.set("max", function("max", (callee, self, args) -> extreme(args, true)));
        math.set("min", function("min", (callee, self, args) -> extreme(args, false)));
        g.set("Math", math);

        JsFunction string = function("String", (callee, self, args) -> JS.str(arg(args, 0)));
        string.props().set("fromCharCode", function("fromCharCode", (callee, self, args) ->
                String.valueOf((char) JS.toNumber(arg(args, 0)))));
        g.set("String", string);

        JsFunction number = function("Number", (callee, self, args) -> Long.valueOf(JS.toNumber(arg(args, 0))));
        number.props().set("isInteger", function("isInteger", (callee, self, args) -> JS.bool(arg(args, 0) instanceof Long)));
        number.props().set("MAX_SAFE_INTEGER", Long.valueOf(9007199254740991L));
        g.set("Number", number);

        g.set("parseInt", function("parseInt", (callee, self, args) ->
                Long.valueOf(JS.parseInteger(JS.str(arg(args, 0)), false))));

        installObject(g);
        installErrors(g);

        JsFunction array = constructor("Array", (callee, self, args) -> {
            JsArray a = new JsArray();
            if (args.length == 1 && args[0] instanceof Long) {
                a.setLength((int) ((Long) args[0]).longValue());
            } else {
                for (Object v : args) {
                    a.add(v);
                }
            }
            return a;
        });
        JsObject arrayPrototype = new JsObject();
        arrayPrototype.proto = objectPrototype;
        arrayPrototype.setHidden("constructor", array);
        array.setPrototype(arrayPrototype);
        array.props().setHidden("isArray", function("isArray", (callee, self, args) -> JS.bool(arg(args, 0) instanceof JsArray)));
        array.props().setHidden("of", function("of", (callee, self, args) -> new JsArray(args)));
        g.set("Array", array);

        JsFunction fn = constructor("Function", (callee, self, args) -> {
            throw new JsError("Error: the Function constructor is not supported: no code is compiled at run time");
        });
        g.set("Function", fn);
    }

    private static void installObject(JsObject g) {
        JsObject prototype = new JsObject();
        objectPrototype = prototype;
        JsFunction object = constructor("Object", (callee, self, args) -> {
            Object v = arg(args, 0);
            if (v instanceof JsObject || v instanceof JsArray || v instanceof JsFunction) {
                return v;
            }
            return self instanceof JsObject ? self : JS.object();
        });
        object.setPrototype(prototype);
        prototype.setHidden("constructor", object);
        prototype.setHidden("hasOwnProperty", function("hasOwnProperty", (callee, self, args) ->
                JS.bool(hasOwn(self, JS.keyString(arg(args, 0))))));
        prototype.setHidden("isPrototypeOf", function("isPrototypeOf", (callee, self, args) -> {
            Object v = arg(args, 0);
            if (v instanceof JsObject) {
                for (JsObject p = ((JsObject) v).proto; p != null; p = p.proto) {
                    if (p == self) {
                        return Boolean.TRUE;
                    }
                }
            }
            return Boolean.FALSE;
        }));
        prototype.setHidden("propertyIsEnumerable", function("propertyIsEnumerable", (callee, self, args) -> {
            String key = JS.keyString(arg(args, 0));
            return JS.bool(self instanceof JsObject ? ((JsObject) self).isEnumerable(key) : hasOwn(self, key));
        }));
        prototype.setHidden("toString", function("toString", (callee, self, args) -> "[object " + tag(self) + "]"));
        prototype.setHidden("valueOf", function("valueOf", (callee, self, args) -> self));

        JsObject statics = object.props();
        statics.setHidden("keys", function("keys", (callee, self, args) -> enumerate(arg(args, 0), 0)));
        statics.setHidden("values", function("values", (callee, self, args) -> enumerate(arg(args, 0), 1)));
        statics.setHidden("entries", function("entries", (callee, self, args) -> enumerate(arg(args, 0), 2)));
        statics.setHidden("hasOwn", function("hasOwn", (callee, self, args) ->
                JS.bool(hasOwn(arg(args, 0), JS.keyString(arg(args, 1))))));
        statics.setHidden("assign", function("assign", (callee, self, args) -> {
            Object target = arg(args, 0);
            for (int i = 1; i < args.length; i++) {
                JsArray keys = (JsArray) enumerate(args[i], 0);
                for (int k = 0; k < keys.length(); k++) {
                    JS.set(target, keys.get(k), JS.get(args[i], keys.get(k)));
                }
            }
            return target;
        }));
        statics.setHidden("create", function("create", (callee, self, args) -> {
            Object proto = arg(args, 0);
            if (!(proto instanceof JsObject) && proto != JS.NULL) {
                throw new JsError("TypeError: Object prototype may only be an Object or null: " + JS.str(proto));
            }
            JsObject o = new JsObject();
            o.proto = proto == JS.NULL ? null : (JsObject) proto;
            return o;
        }));
        statics.setHidden("getPrototypeOf", function("getPrototypeOf", (callee, self, args) -> {
            Object v = arg(args, 0);
            if (v instanceof JsObject) {
                JsObject p = ((JsObject) v).proto;
                return p == null ? JS.NULL : p;
            }
            if (v instanceof JsArray) {
                return ((JsFunction) lookup("Array")).prototype();
            }
            return prototype;
        }));
        statics.setHidden("setPrototypeOf", function("setPrototypeOf", (callee, self, args) -> {
            Object v = arg(args, 0);
            Object proto = arg(args, 1);
            if (v instanceof JsObject) {
                ((JsObject) v).proto = proto == JS.NULL ? null : (JsObject) proto;
            }
            return v;
        }));
        table().set("Object", object);
    }

    private static void installErrors(JsObject g) {
        JsObject errorPrototype = null;
        for (String type : ERROR_TYPES) {
            JsObject prototype = new JsObject();
            prototype.proto = errorPrototype == null ? objectPrototype : errorPrototype;
            prototype.setHidden("name", type);
            prototype.setHidden("message", "");
            JsFunction ctor = new JsFunction(type,
                    (callee, self, args) -> initError(new JsObject(), callee.prototype(), args),
                    (callee, self, args) -> initError((JsObject) self, callee.prototype(), args));
            ctor.setPrototype(prototype);
            prototype.setHidden("constructor", ctor);
            if (errorPrototype == null) {
                errorPrototype = prototype;
                prototype.setHidden("toString", function("toString", (callee, self, args) -> errorText(self)));
            }
            g.set(type, ctor);
        }
    }

    /** Fills in an Error: an own, non-enumerable {@code message} if one was given, and a {@code stack}. */
    private static Object initError(JsObject e, JsObject prototype, Object[] args) {
        e.proto = prototype;
        Object message = arg(args, 0);
        if (message != null) {
            e.setHidden("message", JS.str(message));
        }
        e.setHidden("stack", errorText(e));
        return e;
    }

    /** {@code Error.prototype.toString}: "Name: message", or just one of them if the other is empty. */
    static String errorText(Object e) {
        Object name = JS.get(e, "name");
        Object message = JS.get(e, "message");
        String n = name == null ? "Error" : JS.str(name);
        String m = message == null ? "" : JS.str(message);
        return n.isEmpty() ? m : m.isEmpty() ? n : n + ": " + m;
    }

    static boolean isErrorType(String name) {
        for (String type : ERROR_TYPES) {
            if (type.equals(name)) {
                return true;
            }
        }
        return false;
    }

    /** True for an Error object: one whose prototype chain includes {@code Error.prototype}. */
    static boolean isError(Object v) {
        if (!(v instanceof JsObject)) {
            return false;
        }
        JsObject target = ((JsFunction) lookup("Error")).prototype();
        for (JsObject p = ((JsObject) v).proto; p != null; p = p.proto) {
            if (p == target) {
                return true;
            }
        }
        return false;
    }

    /** A new error of a built-in type, for the runtime to throw as JavaScript sees it. */
    static Object makeError(String type, String message) {
        JsFunction ctor = (JsFunction) lookup(type);
        return ctor.construct(new Object[] {message});
    }

    private static String tag(Object v) {
        if (v == null) {
            return "Undefined";
        }
        if (v == JS.NULL) {
            return "Null";
        }
        if (v instanceof JsArray) {
            return "Array";
        }
        if (v instanceof JsFunction) {
            return "Function";
        }
        if (v instanceof String) {
            return "String";
        }
        if (v instanceof Long) {
            return "Number";
        }
        if (v instanceof Boolean) {
            return "Boolean";
        }
        return "Object";
    }

    /** {@code self.hasOwnProperty(key)} for any value. */
    private static boolean hasOwn(Object o, String key) {
        if (o instanceof JsObject) {
            return ((JsObject) o).hasOwn(key);
        }
        if (o instanceof JsArray) {
            return JsObject.isIndex(key) ? Long.parseLong(key) < ((JsArray) o).length() : key.equals("length");
        }
        if (o instanceof String) {
            return JsObject.isIndex(key) ? Long.parseLong(key) < ((String) o).length() : key.equals("length");
        }
        if (o instanceof JsFunction) {
            JsFunction f = (JsFunction) o;
            return key.equals("name") || key.equals("prototype") && f.isConstructible() || f.hasProps() && f.props().hasOwn(key);
        }
        return false;
    }

    /** Object.keys (kind 0), values (1) and entries (2): own enumerable properties, in enumeration order. */
    private static Object enumerate(Object o, int kind) {
        JsArray keys = (JsArray) JS.forInKeys(o instanceof JsObject ? ownOnly(o) : o);
        JsArray out = new JsArray();
        for (int i = 0; i < keys.length(); i++) {
            Object key = keys.get(i);
            if (kind == 0) {
                out.add(key);
            } else if (kind == 1) {
                out.add(JS.get(o, key));
            } else {
                out.add(new JsArray(new Object[] {key, JS.get(o, key)}));
            }
        }
        return out;
    }

    /** A copy of {@code o} without its prototype, so key enumeration stays on the object itself. */
    private static JsObject ownOnly(Object o) {
        JsObject source = (JsObject) o;
        JsObject copy = new JsObject();
        for (String key : source.keys()) {
            copy.set(key, source.getOwn(key));
        }
        return copy;
    }

    private static Object extreme(Object[] args, boolean max) {
        if (args.length == 0) {
            throw JS.nan("Math." + (max ? "max" : "min") + " of no arguments");
        }
        long best = JS.toNumber(args[0]);
        for (int i = 1; i < args.length; i++) {
            long x = JS.toNumber(args[i]);
            if (max ? x > best : x < best) {
                best = x;
            }
        }
        return Long.valueOf(best);
    }
}
