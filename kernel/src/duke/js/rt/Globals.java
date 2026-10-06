package duke.js.rt;

/** The global names every program sees: console, Math, String and friends. The kernel adds {@code Kernel}. */
public final class Globals {

    /** Where console.log writes. The kernel points it at the console; tests point it at stdout. */
    public interface Sink {
        void print(String s);
    }

    private static Sink sink;
    private static JsObject table;

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

        JsObject array = new JsObject();
        array.set("isArray", function("isArray", (callee, self, args) -> JS.bool(arg(args, 0) instanceof JsArray)));
        g.set("Array", array);

        JsObject object = new JsObject();
        object.set("keys", function("keys", (callee, self, args) -> keys(arg(args, 0), false)));
        object.set("values", function("values", (callee, self, args) -> keys(arg(args, 0), true)));
        g.set("Object", object);
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

    private static Object keys(Object o, boolean values) {
        JsArray r = new JsArray();
        if (o instanceof JsObject) {
            JsObject object = (JsObject) o;
            for (int i = 0; i < object.size(); i++) {
                r.add(values ? object.valueAt(i) : object.keyAt(i));
            }
        } else if (o instanceof JsArray && !values) {
            for (int i = 0; i < ((JsArray) o).length(); i++) {
                r.add(String.valueOf(i));
            }
        } else if (o instanceof JsArray) {
            JsArray a = (JsArray) o;
            for (int i = 0; i < a.length(); i++) {
                r.add(a.get(i));
            }
        }
        return r;
    }
}
