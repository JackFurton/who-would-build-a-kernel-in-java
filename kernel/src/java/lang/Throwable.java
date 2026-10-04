package java.lang;

import duke.kernel.Console;
import duke.rt.Backtrace;

public class Throwable {

    private final String message;
    private Throwable cause = this;
    private long[] stackTrace;
    private Throwable[] suppressed;

    public Throwable() {
        this(null, null);
        cause = this;
    }

    public Throwable(String message) {
        this(message, null);
        cause = this;
    }

    public Throwable(String message, Throwable cause) {
        this.message = message;
        this.cause = cause;
        fillInStackTrace();
    }

    public Throwable(Throwable cause) {
        this(cause == null ? null : cause.toString(), cause);
    }

    public String getMessage() {
        return message;
    }

    public String getLocalizedMessage() {
        return getMessage();
    }

    /** {@code cause == this} means "not set yet", as in the JDK, so initCause can still set it once. */
    public Throwable getCause() {
        return cause == this ? null : cause;
    }

    public Throwable initCause(Throwable cause) {
        if (this.cause != this) {
            throw new IllegalStateException("Can't overwrite cause with " + (cause == null ? "a null" : cause.toString()), this);
        }
        if (cause == this) {
            throw new IllegalArgumentException("Self-causation not permitted");
        }
        this.cause = cause;
        return this;
    }

    /** Return addresses of the frames above the outermost Throwable constructor. */
    public Throwable fillInStackTrace() {
        stackTrace = Backtrace.capture();
        return this;
    }

    public final void addSuppressed(Throwable exception) {
        if (exception == this) {
            throw new IllegalArgumentException("Self-suppression not permitted");
        }
        if (exception == null) {
            throw new NullPointerException("Cannot suppress a null exception.");
        }
        int n = suppressed == null ? 0 : suppressed.length;
        Throwable[] grown = new Throwable[n + 1];
        if (n > 0) {
            System.arraycopy(suppressed, 0, grown, 0, n);
        }
        grown[n] = exception;
        suppressed = grown;
    }

    public final Throwable[] getSuppressed() {
        if (suppressed == null) {
            return new Throwable[0];
        }
        Throwable[] copy = new Throwable[suppressed.length];
        System.arraycopy(suppressed, 0, copy, 0, copy.length);
        return copy;
    }

    @Override
    public String toString() {
        String m = getLocalizedMessage();
        return m == null ? getClass().getName() : getClass().getName() + ": " + m;
    }

    public StackTraceElement[] getStackTrace() {
        int n = stackTrace == null ? 0 : stackTrace.length;
        StackTraceElement[] elements = new StackTraceElement[n];
        for (int i = 0; i < n; i++) {
            elements[i] = Backtrace.element(stackTrace[i]);
        }
        return elements;
    }

    public void printStackTrace() {
        Console.println(toString());
        printEnclosedTrace();
    }

    /** The frames and causes, without the first line: also how an uncaught exception's panic ends. */
    public void printEnclosedTrace() {
        for (StackTraceElement e : getStackTrace()) {
            Console.println("  at " + e);
        }
        for (Throwable c = getCause(); c != null; c = c.getCause()) {
            Console.println("Caused by: " + c);
            for (StackTraceElement e : c.getStackTrace()) {
                Console.println("  at " + e);
            }
        }
    }
}
