package java.lang;

public class AssertionError extends Error {

    public AssertionError() {
    }

    public AssertionError(Object detail) {
        super(String.valueOf(detail));
        if (detail instanceof Throwable t) {
            initCause(t);
        }
    }

    public AssertionError(String message, Throwable cause) {
        super(message, cause);
    }
}
