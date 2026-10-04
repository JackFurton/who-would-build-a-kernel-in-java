package java.lang;

/**
 * javac needs this for any generic type declaration. Nothing can be thrown yet (#10), so it only
 * carries a message.
 */
public class Throwable {

    private final String message;

    public Throwable() {
        this(null);
    }

    public Throwable(String message) {
        this.message = message;
    }

    public String getMessage() {
        return message;
    }
}
