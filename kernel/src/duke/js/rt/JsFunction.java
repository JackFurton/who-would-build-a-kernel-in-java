package duke.js.rt;

/** A JavaScript function value: compiled code, an optional bag of properties, and a prototype if it can be constructed. */
public final class JsFunction {

    /** The compiled body. {@code callee} is the function itself, {@code self} its {@code this}. */
    public interface Body {
        Object call(JsFunction callee, Object self, Object[] args);
    }

    final String name;
    private final Body body;
    private final boolean constructible;
    /** What {@code new} runs instead of {@code body}, for built-ins that behave differently when constructed. */
    private final Body constructBody;
    private JsObject props;
    private JsObject prototype;

    /** A function that cannot be used with {@code new}: arrow functions and most built-ins. */
    public JsFunction(String name, Body body) {
        this(name, body, false);
    }

    public JsFunction(String name, Body body, boolean constructible) {
        this(name, body, constructible, null);
    }

    /** A built-in whose {@code new f()} runs {@code constructBody} on the fresh object, and whose plain call runs {@code body}. */
    public JsFunction(String name, Body body, Body constructBody) {
        this(name, body, true, constructBody);
    }

    private JsFunction(String name, Body body, boolean constructible, Body constructBody) {
        this.name = name;
        this.body = body;
        this.constructible = constructible;
        this.constructBody = constructBody;
    }

    public Object call(Object self, Object[] args) {
        return body.call(this, self, args);
    }

    /** {@code new f(...args)}: a fresh object whose prototype is {@code f.prototype}, unless the body returns an object. */
    public Object construct(Object[] args) {
        if (!constructible) {
            throw new JsError("TypeError: " + (name.isEmpty() ? "anonymous" : name) + " is not a constructor");
        }
        JsObject self = new JsObject();
        self.proto = prototype();
        Object result = (constructBody != null ? constructBody : body).call(this, self, args);
        return result instanceof JsObject || result instanceof JsArray || result instanceof JsFunction ? result : self;
    }

    public String name() {
        return name;
    }

    public boolean isConstructible() {
        return constructible;
    }

    /** {@code f.prototype}, created with a {@code constructor} link on first use; null if f can't be constructed. */
    public JsObject prototype() {
        if (prototype == null && constructible) {
            prototype = new JsObject();
            prototype.proto = Globals.objectPrototype();
            prototype.setHidden("constructor", this);
        }
        return prototype;
    }

    void setPrototype(JsObject p) {
        prototype = p;
    }

    JsObject props() {
        if (props == null) {
            props = new JsObject();
        }
        return props;
    }

    boolean hasProps() {
        return props != null;
    }
}
