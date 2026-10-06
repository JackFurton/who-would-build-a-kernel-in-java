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
    /** For a class: its superclass, whose statics it inherits. Null for a base class. */
    private JsFunction parent;
    private boolean isClass;
    private boolean derived;
    /** The instance field initializers of a class, run on each new instance. */
    private Body fieldInit;
    /** The object a method was defined on (a prototype, or a class for statics): where {@code super} lookups start. */
    Object home;

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

    /** A class: callable only with {@code new}, with its fields, superclass and statics. */
    static JsFunction classFunction(String name, Body constructor, Body fields, boolean derived, JsFunction parent) {
        JsFunction f = new JsFunction(name, constructor, true, null);
        f.isClass = true;
        f.derived = derived;
        f.parent = parent;
        f.fieldInit = fields;
        return f;
    }

    public Object call(Object self, Object[] args) {
        if (isClass) {
            throw new JsError("TypeError: Class constructor " + name + " cannot be invoked without 'new'");
        }
        return body.call(this, self, args);
    }

    /** Runs this function's constructor logic on an object another constructor allocated: what {@code super(...)} does. */
    void initialize(JsObject self, Object[] args) {
        if (isClass && !derived) {
            runFieldInit(self);
        }
        (constructBody != null ? constructBody : body).call(this, self, args);
    }

    void runFieldInit(Object self) {
        if (fieldInit != null) {
            fieldInit.call(this, self, new Object[0]);
        }
    }

    JsFunction parent() {
        return parent;
    }

    boolean isClass() {
        return isClass;
    }

    boolean isDerived() {
        return derived;
    }

    /** {@code new f(...args)}: a fresh object whose prototype is {@code f.prototype}, unless the body returns an object. */
    public Object construct(Object[] args) {
        if (!constructible) {
            throw new JsError("TypeError: " + (name.isEmpty() ? "anonymous" : name) + " is not a constructor");
        }
        JsObject self = new JsObject();
        self.proto = prototype();
        if (isClass && !derived) {
            runFieldInit(self);
        }
        Object result = (constructBody != null ? constructBody : body).call(this, self, args);
        return JS.isObject(result) ? result : self;
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
