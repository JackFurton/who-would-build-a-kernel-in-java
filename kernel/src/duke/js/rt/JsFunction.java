package duke.js.rt;

/** A JavaScript function value: compiled code plus an optional bag of properties. */
public final class JsFunction {

    /** The compiled body. {@code callee} is the function itself, {@code self} its {@code this}. */
    public interface Body {
        Object call(JsFunction callee, Object self, Object[] args);
    }

    final String name;
    private final Body body;
    private JsObject props;

    public JsFunction(String name, Body body) {
        this.name = name;
        this.body = body;
    }

    public Object call(Object self, Object[] args) {
        return body.call(this, self, args);
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
