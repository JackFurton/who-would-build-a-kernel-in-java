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
    private JsObject props;
    private JsObject prototype;

    /** A function that cannot be used with {@code new}: arrow functions and most built-ins. */
    public JsFunction(String name, Body body) {
        this(name, body, false);
    }

    public JsFunction(String name, Body body, boolean constructible) {
        this.name = name;
        this.body = body;
        this.constructible = constructible;
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
        Object result = body.call(this, self, args);
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
