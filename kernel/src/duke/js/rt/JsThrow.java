package duke.js.rt;

/** A JavaScript {@code throw}: any value can be thrown, so the exception just carries it. */
public final class JsThrow extends RuntimeException {

    public final Object value;

    public JsThrow(Object value) {
        super(describe(value));
        this.value = value;
    }

    /** What an uncaught throw prints: {@code Uncaught Error: message} for errors, else the value as console.log shows it. */
    private static String describe(Object value) {
        return "Uncaught " + (Globals.isError(value) ? JS.str(value) : Inspect.format(value, 0));
    }
}
