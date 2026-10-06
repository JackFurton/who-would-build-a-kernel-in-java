package duke.js.rt;

/** A JavaScript error that nothing can catch yet: the shell reports it and carries on. */
public final class JsError extends RuntimeException {

    public JsError(String message) {
        super(message);
    }
}
