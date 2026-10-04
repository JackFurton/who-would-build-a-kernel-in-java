package java.lang.invoke;

/** Only so javac can compile lambdas; dukec replaces every lambda call site at build time (#41). */
public final class MethodHandles {

    private MethodHandles() {
    }

    public static final class Lookup {

        private Lookup() {
        }
    }
}
