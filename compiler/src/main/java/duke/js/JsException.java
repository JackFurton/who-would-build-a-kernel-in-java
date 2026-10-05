package duke.js;

/** A syntax or unsupported-feature error in a JavaScript source, with its location. */
public final class JsException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public JsException(String file, int line, String message) {
        super(file + ":" + line + ": " + message);
    }
}
