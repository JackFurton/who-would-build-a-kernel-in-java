package duke.ktest;

/** Failures throw AssertionError, which the generated runner catches per test. */
public final class Assert {

    private Assert() {
    }

    public static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static void assertEquals(long expected, long actual, String message) {
        if (expected != actual) {
            throw new AssertionError(message + ": expected " + expected + ", got " + actual);
        }
    }

    public static void assertEquals(Object expected, Object actual, String message) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(message + ": expected " + expected + ", got " + actual);
        }
    }

    public interface Body {
        void run();
    }

    public static <T extends Throwable> T assertThrows(Class<T> type, Body body, String message) {
        try {
            body.run();
        } catch (Throwable t) {
            if (type.isInstance(t)) {
                @SuppressWarnings("unchecked")
                T matched = (T) t;
                return matched;
            }
            throw new AssertionError(message + ": expected " + type.getName() + ", got " + t, t);
        }
        throw new AssertionError(message + ": expected " + type.getName() + ", nothing was thrown");
    }
}
