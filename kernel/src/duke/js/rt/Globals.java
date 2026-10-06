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
    private static JsObject iteratorPrototype;
    private static JsObject generatorPrototype;

    static final JsSymbol ITERATOR = new JsSymbol("Symbol.iterator");
    static final JsSymbol ASYNC_ITERATOR = new JsSymbol("Symbol.asyncIterator");
    static final JsSymbol HAS_INSTANCE = new JsSymbol("Symbol.hasInstance");
    static final JsSymbol TO_STRING_TAG = new JsSymbol("Symbol.toStringTag");
    private static JsObject symbolRegistry;
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

        JsFunction string = function("String", (callee, self, args) ->
                arg(args, 0) instanceof JsSymbol ? arg(args, 0).toString() : JS.str(arg(args, 0)));
        string.props().set("fromCharCode", function("fromCharCode", (callee, self, args) ->
                String.valueOf((char) JS.toNumber(arg(args, 0)))));
        string.props().setHidden("raw", function("raw", (callee, self, args) -> {
            Object raw = JS.get(arg(args, 0), "raw");
            StringBuilder sb = new StringBuilder();
            long n = JS.length(raw);
            for (long i = 0; i < n; i++) {
                sb.append(JS.str(JS.getIndex(raw, i)));
                if (i + 1 < n && i + 1 < args.length) {
                    sb.append(JS.str(args[(int) i + 1]));
                }
            }
            return sb.toString();
        }));
        g.set("String", string);

        JsFunction number = function("Number", (callee, self, args) -> Long.valueOf(JS.toNumber(arg(args, 0))));
        number.props().set("isInteger", function("isInteger", (callee, self, args) -> JS.bool(arg(args, 0) instanceof Long)));
        number.props().set("MAX_SAFE_INTEGER", Long.valueOf(9007199254740991L));
        g.set("Number", number);

        g.set("parseInt", function("parseInt", (callee, self, args) ->
                Long.valueOf(JS.parseInteger(JS.str(arg(args, 0)), false))));

        installObject(g);
        installErrors(g);
        installSymbol(g);
        installCollections(g);

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
                JS.bool(hasOwn(self, JS.key(arg(args, 0))))));
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
            Object key = JS.key(arg(args, 0));
            return JS.bool(self instanceof JsObject ? ((JsObject) self).isEnumerable(key) : hasOwn(self, key));
        }));
        prototype.setHidden("toString", function("toString", (callee, self, args) -> "[object " + tag(self) + "]"));
        prototype.setHidden("valueOf", function("valueOf", (callee, self, args) -> self));

        JsObject statics = object.props();
        statics.setHidden("keys", function("keys", (callee, self, args) -> enumerate(arg(args, 0), 0)));
        statics.setHidden("values", function("values", (callee, self, args) -> enumerate(arg(args, 0), 1)));
        statics.setHidden("entries", function("entries", (callee, self, args) -> enumerate(arg(args, 0), 2)));
        statics.setHidden("hasOwn", function("hasOwn", (callee, self, args) ->
                JS.bool(hasOwn(arg(args, 0), JS.key(arg(args, 1))))));
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
            if (v instanceof JsFunction && ((JsFunction) v).parent() != null) {
                return ((JsFunction) v).parent();
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
        installProperties(statics);
        table().set("Object", object);
    }

    /** The statics that deal in property attributes and extensibility. */
    private static void installProperties(JsObject statics) {
        statics.setHidden("defineProperty", function("defineProperty", (callee, self, args) -> {
            define(arg(args, 0), JS.key(arg(args, 1)), arg(args, 2));
            return arg(args, 0);
        }));
        statics.setHidden("defineProperties", function("defineProperties", (callee, self, args) -> {
            Object descriptors = arg(args, 1);
            JsArray keys = (JsArray) enumerate(descriptors, 0);
            for (int i = 0; i < keys.length(); i++) {
                define(arg(args, 0), (String) keys.get(i), JS.get(descriptors, keys.get(i)));
            }
            return arg(args, 0);
        }));
        statics.setHidden("getOwnPropertyDescriptor", function("getOwnPropertyDescriptor", (callee, self, args) ->
                describe(arg(args, 0), JS.key(arg(args, 1)))));
        statics.setHidden("getOwnPropertyDescriptors", function("getOwnPropertyDescriptors", (callee, self, args) -> {
            JsObject out = (JsObject) JS.object();
            JsArray names = (JsArray) ownNames(arg(args, 0));
            for (int i = 0; i < names.length(); i++) {
                out.set((String) names.get(i), describe(arg(args, 0), (String) names.get(i)));
            }
            return out;
        }));
        statics.setHidden("getOwnPropertySymbols", function("getOwnPropertySymbols", (callee, self, args) -> {
            JsArray out = new JsArray();
            JsObject h = holder(arg(args, 0), false);
            if (h != null) {
                for (JsSymbol symbol : h.symbolKeys(true)) {
                    out.add(symbol);
                }
            }
            return out;
        }));
        statics.setHidden("getOwnPropertyNames", function("getOwnPropertyNames", (callee, self, args) ->
                ownNames(arg(args, 0))));
        statics.setHidden("freeze", function("freeze", (callee, self, args) -> lock(arg(args, 0), true)));
        statics.setHidden("seal", function("seal", (callee, self, args) -> lock(arg(args, 0), false)));
        statics.setHidden("preventExtensions", function("preventExtensions", (callee, self, args) -> {
            Object o = arg(args, 0);
            if (o instanceof JsObject) {
                ((JsObject) o).preventExtensions();
            } else if (o instanceof JsArray) {
                ((JsArray) o).lock(false);
            }
            return o;
        }));
        statics.setHidden("isFrozen", function("isFrozen", (callee, self, args) -> JS.bool(isLocked(arg(args, 0), true))));
        statics.setHidden("isSealed", function("isSealed", (callee, self, args) -> JS.bool(isLocked(arg(args, 0), false))));
        statics.setHidden("isExtensible", function("isExtensible", (callee, self, args) -> {
            Object o = arg(args, 0);
            return JS.bool(o instanceof JsObject ? ((JsObject) o).isExtensible() : o instanceof JsArray && !((JsArray) o).isSealed());
        }));
        statics.setHidden("is", function("is", (callee, self, args) -> JS.bool(JS.seq(arg(args, 0), arg(args, 1)))));
        statics.setHidden("fromEntries", function("fromEntries", (callee, self, args) -> {
            JsArray entries = (JsArray) JS.toArray(arg(args, 0));
            JsObject out = (JsObject) JS.object();
            for (int i = 0; i < entries.length(); i++) {
                JsArray pair = (JsArray) JS.toArray(entries.get(i));
                out.set(JS.keyString(pair.get(0)), pair.get(1));
            }
            return out;
        }));
    }

    private static Object lock(Object o, boolean readonly) {
        if (o instanceof JsObject) {
            ((JsObject) o).lock(readonly);
        } else if (o instanceof JsArray) {
            ((JsArray) o).lock(readonly);
        } else if (o instanceof JsFunction && ((JsFunction) o).hasProps()) {
            ((JsFunction) o).props().lock(readonly);
        }
        return o;
    }

    private static boolean isLocked(Object o, boolean readonly) {
        if (o instanceof JsObject) {
            return ((JsObject) o).isLocked(readonly);
        }
        if (o instanceof JsArray) {
            return readonly ? ((JsArray) o).isFrozen() : ((JsArray) o).isSealed();
        }
        return !(o instanceof JsFunction);
    }

    /** The object that holds {@code o}'s named properties, or null (strings and numbers have none). */
    private static JsObject holder(Object o, boolean create) {
        if (o instanceof JsObject) {
            return (JsObject) o;
        }
        if (o instanceof JsFunction) {
            return ((JsFunction) o).props();
        }
        if (o instanceof JsArray) {
            return create ? ((JsArray) o).namedOrCreate() : ((JsArray) o).named();
        }
        return null;
    }

    private static Object ownNames(Object o) {
        JsArray out = new JsArray();
        if (o instanceof JsArray) {
            for (int i = 0; i < ((JsArray) o).length(); i++) {
                out.add(Long.toString(i));
            }
            out.add("length");
        } else if (o instanceof String) {
            for (int i = 0; i < ((String) o).length(); i++) {
                out.add(Long.toString(i));
            }
            out.add("length");
        }
        JsObject h = holder(o, false);
        if (h != null) {
            for (String key : h.allKeys()) {
                out.add(key);
            }
        }
        return out;
    }

    /** {@code Object.defineProperty(target, key, descriptor)}. */
    private static void define(Object target, Object key, Object descriptor) {
        JsObject holder = holder(target, true);
        if (holder == null) {
            throw new JsError("TypeError: Object.defineProperty called on non-object");
        }
        if (!(descriptor instanceof JsObject)) {
            throw new JsError("TypeError: Property description must be an object: " + JS.str(descriptor));
        }
        JsObject d = (JsObject) descriptor;
        boolean exists = holder.hasOwn(key);
        if (!exists && !holder.isExtensible()) {
            throw new JsError("TypeError: Cannot define property " + key + ", object is not extensible");
        }
        if (exists && !holder.isConfigurable(key)) {
            // A non-configurable property may only have its value changed, and only if it is writable.
            boolean changesShape = d.has("get") || d.has("set") || d.has("enumerable") && JS.truthy(d.get("enumerable")) != holder.isEnumerable(key)
                    || d.has("configurable") && JS.truthy(d.get("configurable"));
            boolean changesValue = d.has("value") && !JS.seq(d.get("value"), holder.getOwn(key));
            if (changesShape || changesValue && !holder.isWritable(key) || d.has("writable") && JS.truthy(d.get("writable")) && !holder.isWritable(key)) {
                throw new JsError("TypeError: Cannot redefine property: " + key);
            }
        }
        boolean enumerable = d.has("enumerable") ? JS.truthy(d.get("enumerable")) : exists && holder.isEnumerable(key);
        boolean configurable = d.has("configurable") ? JS.truthy(d.get("configurable")) : exists && holder.isConfigurable(key);
        if (d.has("get") || d.has("set")) {
            Object getter = d.get("get");
            Object setter = d.get("set");
            if (getter != null && !(getter instanceof JsFunction) || setter != null && !(setter instanceof JsFunction)) {
                throw new JsError("TypeError: Getter and setter must be functions");
            }
            holder.define(key, new JsObject.Accessor((JsFunction) getter, (JsFunction) setter), enumerable, true, configurable);
            return;
        }
        boolean writable = d.has("writable") ? JS.truthy(d.get("writable")) : exists && holder.isWritable(key);
        Object value = d.has("value") ? d.get("value") : exists ? holder.getOwn(key) : null;
        if (target instanceof JsArray && isIndexKey(key)) {
            ((JsArray) target).set((int) Long.parseLong((String) key), value);
            return;
        }
        holder.define(key, value, enumerable, writable, configurable);
    }

    /** {@code Object.getOwnPropertyDescriptor}: undefined, or an object describing the property. */
    private static Object describe(Object o, Object key) {
        JsObject d = (JsObject) JS.object();
        if (o instanceof JsArray && (isIndexKey(key) || "length".equals(key))) {
            JsArray a = (JsArray) o;
            if ("length".equals(key)) {
                d.set("value", Long.valueOf(a.length()));
                d.set("writable", JS.bool(!a.isFrozen()));
                d.set("enumerable", Boolean.FALSE);
                d.set("configurable", Boolean.FALSE);
                return d;
            }
            if (Long.parseLong((String) key) >= a.length()) {
                return null;
            }
            d.set("value", a.get((int) Long.parseLong((String) key)));
            d.set("writable", JS.bool(!a.isFrozen()));
            d.set("enumerable", Boolean.TRUE);
            d.set("configurable", JS.bool(!a.isSealed()));
            return d;
        }
        JsObject h = holder(o, false);
        if (h == null || !h.hasOwn(key)) {
            return null;
        }
        Object raw = h.getOwnRaw(key);
        if (raw instanceof JsObject.Accessor) {
            JsObject.Accessor accessor = (JsObject.Accessor) raw;
            d.set("get", accessor.getter);
            d.set("set", accessor.setter);
        } else {
            d.set("value", raw);
            d.set("writable", JS.bool(h.isWritable(key)));
        }
        d.set("enumerable", JS.bool(h.isEnumerable(key)));
        d.set("configurable", JS.bool(h.isConfigurable(key)));
        return d;
    }

    /** Map, Set, WeakMap and WeakSet: constructors that take an optional iterable of entries (or values). */
    private static void installCollections(JsObject g) {
        for (int kind = 0; kind < 4; kind++) {
            boolean isSet = kind % 2 == 1;
            boolean weak = kind >= 2;
            String name = (weak ? "Weak" : "") + (isSet ? "Set" : "Map");
            JsFunction ctor = new JsFunction(name,
                    (callee, self, args) -> {
                        throw new JsError("TypeError: Constructor " + name + " requires 'new'");
                    },
                    (callee, self, args) -> {
                        JsMap m = new JsMap(isSet, weak);
                        Object source = arg(args, 0);
                        if (!JS.nullish(source)) {
                            JsIter it = JS.iter(source);
                            try {
                                while (it.next()) {
                                    Object item = it.value();
                                    if (isSet) {
                                        JS.invoke(m, "add", item);
                                    } else {
                                        if (!JS.isObject(item)) {
                                            throw new JsError("TypeError: Iterator value " + JS.str(item) + " is not an entry object");
                                        }
                                        JS.invoke(m, "set", JS.getIndex(item, 0), JS.getIndex(item, 1));
                                    }
                                }
                            } finally {
                                it.close();
                            }
                        }
                        return m;
                    });
            JsObject prototype = new JsObject();
            prototype.proto = objectPrototype;
            prototype.setHidden("constructor", ctor);
            ctor.setPrototype(prototype);
            g.set(name, ctor);
        }
    }

    private static void installSymbol(JsObject g) {
        iteratorPrototype = new JsObject();
        iteratorPrototype.proto = objectPrototype;
        iteratorPrototype.setHidden(ITERATOR, function("[Symbol.iterator]", (callee, self, args) -> self));
        generatorPrototype = new JsObject();
        generatorPrototype.proto = iteratorPrototype;
        generatorPrototype.setHidden(TO_STRING_TAG, "Generator");
        symbolRegistry = new JsObject();
        JsFunction symbol = function("Symbol", (callee, self, args) -> {
            Object description = arg(args, 0);
            return new JsSymbol(description == null ? null : JS.str(description));
        });
        JsObject statics = symbol.props();
        statics.setHidden("iterator", ITERATOR);
        statics.setHidden("asyncIterator", ASYNC_ITERATOR);
        statics.setHidden("hasInstance", HAS_INSTANCE);
        statics.setHidden("toStringTag", TO_STRING_TAG);
        statics.setHidden("for", function("for", (callee, self, args) -> {
            String key = JS.str(arg(args, 0));
            Object existing = symbolRegistry.getOwn(key);
            if (existing != null) {
                return existing;
            }
            JsSymbol created = new JsSymbol(key);
            symbolRegistry.set(key, created);
            return created;
        }));
        statics.setHidden("keyFor", function("keyFor", (callee, self, args) -> {
            Object sym = arg(args, 0);
            if (!(sym instanceof JsSymbol)) {
                throw new JsError("TypeError: " + JS.typeof(sym) + " is not a symbol");
            }
            String description = ((JsSymbol) sym).description;
            return description != null && symbolRegistry.getOwn(description) == sym ? description : null;
        }));
        g.set("Symbol", symbol);
    }

    /** The object {@code generatorFunction()} returns: next, throw and return drive the state machine. */
    static JsObject generatorObject(JsGenerator generator) {
        JsObject o = new JsObject();
        o.proto = generatorPrototype;
        o.setHidden("next", function("next", (callee, self, args) -> generator.step(0, arg(args, 0))));
        o.setHidden("throw", function("throw", (callee, self, args) -> generator.step(1, arg(args, 0))));
        o.setHidden("return", function("return", (callee, self, args) -> generator.step(2, arg(args, 0))));
        return o;
    }

    /** A JavaScript iterator object over a Java iterator: {@code next()} yields {@code {value, done}}. */
    static JsObject iteratorObject(JsIter it) {
        JsObject o = new JsObject();
        o.proto = iteratorPrototype;
        o.setHidden("next", function("next", (callee, self, args) -> {
            JsObject result = (JsObject) JS.object();
            boolean more = it.next();
            result.set("value", more ? it.value() : null);
            result.set("done", JS.bool(!more));
            return result;
        }));
        return o;
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
        if (e.proto == null) {
            e.proto = prototype;
        }
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
        if (v instanceof JsObject && ((JsObject) v).get(TO_STRING_TAG) instanceof String) {
            return (String) ((JsObject) v).get(TO_STRING_TAG);
        }
        if (v instanceof JsSymbol) {
            return "Symbol";
        }
        if (v == null) {
            return "Undefined";
        }
        if (v == JS.NULL) {
            return "Null";
        }
        if (v instanceof JsArray) {
            return "Array";
        }
        if (v instanceof JsMap) {
            String t = Builtins.mapTag((JsMap) v);
            return t.substring(8, t.length() - 1);
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

    private static boolean isIndexKey(Object key) {
        return key instanceof String && JsObject.isIndex((String) key);
    }

    /** {@code self.hasOwnProperty(key)} for any value. */
    private static boolean hasOwn(Object o, Object key) {
        if (o instanceof JsObject) {
            return ((JsObject) o).hasOwn(key);
        }
        if (o instanceof JsArray) {
            return isIndexKey(key) ? Long.parseLong((String) key) < ((JsArray) o).length() : "length".equals(key);
        }
        if (o instanceof String) {
            return isIndexKey(key) ? Long.parseLong((String) key) < ((String) o).length() : "length".equals(key);
        }
        if (o instanceof JsFunction) {
            JsFunction f = (JsFunction) o;
            return "name".equals(key) || "prototype".equals(key) && f.isConstructible() || f.hasProps() && f.props().hasOwn(key);
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
