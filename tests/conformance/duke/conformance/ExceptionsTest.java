package duke.conformance;

final class ExceptionsTest {

    static final class Custom extends RuntimeException {
        final int code;

        Custom(String message, int code) {
            super(message);
            this.code = code;
        }
    }

    static final class Checked extends Exception {
        Checked(String message) {
            super(message);
        }
    }

    static int zero() {
        return 0;
    }

    static String catchArithmetic() {
        try {
            return "no " + (1 / zero());
        } catch (ArithmeticException e) {
            return e.getClass().getName() + ": " + e.getMessage();
        }
    }

    static String catchArrayIndex() {
        int[] a = new int[3];
        try {
            a[5] = 1;
            return "no";
        } catch (ArrayIndexOutOfBoundsException e) {
            return e.getMessage();
        }
    }

    // HotSpot's helpful NPE messages describe the bytecode, so only the type is compared.
    static String catchNullPointer() {
        String s = null;
        try {
            return "no " + s.length();
        } catch (NullPointerException e) {
            return e.getClass().getName();
        }
    }

    // HotSpot appends module and loader details, so compare the start.
    static boolean catchClassCast() {
        Object o = "string";
        try {
            Integer i = (Integer) o;
            return false;
        } catch (ClassCastException e) {
            return e.getMessage().startsWith("class java.lang.String cannot be cast to class java.lang.Integer");
        }
    }

    static String catchArrayStore() {
        Object[] strings = new String[1];
        try {
            strings[0] = 1;
            return "no";
        } catch (ArrayStoreException e) {
            return e.getMessage();
        }
    }

    static String catchNegativeArraySize() {
        try {
            int[] a = new int[zero() - 5];
            return "no " + a.length;
        } catch (NegativeArraySizeException e) {
            return e.getMessage();
        }
    }

    static String catchBySuperclass() {
        try {
            throw new Custom("boom", 7);
        } catch (RuntimeException e) {
            return e.getMessage() + ((Custom) e).code;
        }
    }

    static String firstMatchingHandlerWins() {
        StringBuilder log = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            try {
                if (i == 0) {
                    throw new IllegalArgumentException("a");
                } else if (i == 1) {
                    throw new IllegalStateException("b");
                }
                throw new UnsupportedOperationException("c");
            } catch (IllegalArgumentException e) {
                log.append("IAE:").append(e.getMessage());
            } catch (IllegalStateException | UnsupportedOperationException e) {
                log.append("multi:").append(e.getMessage());
            }
            log.append(' ');
        }
        return log.toString();
    }

    static String finallyRuns() {
        StringBuilder log = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            try {
                try {
                    if (i == 1) {
                        throw new IllegalStateException("x");
                    }
                    if (i == 2) {
                        continue;
                    }
                    log.append("body");
                } finally {
                    log.append("[finally").append(i).append(']');
                }
            } catch (IllegalStateException e) {
                log.append("caught");
            }
        }
        return log.toString();
    }

    static int finallyOverridesReturn() {
        return finallyReturn();
    }

    @SuppressWarnings("finally")
    private static int finallyReturn() {
        try {
            throw new IllegalStateException();
        } finally {
            return 42;
        }
    }

    static String unwindsAcrossFrames() {
        try {
            level1(4);
            return "no";
        } catch (Custom e) {
            return e.getMessage() + " code " + e.code;
        }
    }

    private static int level1(int n) {
        return level2(n) + 1;
    }

    private static int level2(int n) {
        if (n == 0) {
            throw new Custom("deep", 99);
        }
        return level1(n - 1) * 2;
    }

    static String checkedException() {
        try {
            mayThrow(true);
            return "no";
        } catch (Checked e) {
            return "checked " + e.getMessage();
        }
    }

    private static void mayThrow(boolean doIt) throws Checked {
        if (doIt) {
            throw new Checked("yes");
        }
    }

    static String rethrowWithCause() {
        try {
            try {
                Integer.parseInt("12x");
                return "no";
            } catch (NumberFormatException e) {
                throw new IllegalStateException("wrapped", e);
            }
        } catch (IllegalStateException e) {
            return e.getMessage() + " <- " + e.getCause().getClass().getName() + ": " + e.getCause().getMessage();
        }
    }

    static String numberFormatMessages() {
        String[] inputs = {"", "-", "abc", "99999999999", "+"};
        StringBuilder sb = new StringBuilder();
        for (String in : inputs) {
            try {
                Integer.parseInt(in);
                sb.append("ok;");
            } catch (NumberFormatException e) {
                sb.append(e.getMessage()).append(';');
            }
        }
        try {
            Integer.parseInt("zz", 16);
        } catch (NumberFormatException e) {
            sb.append(e.getMessage());
        }
        return sb.toString();
    }

    static String stringIndexMessages() {
        StringBuilder sb = new StringBuilder();
        try {
            "abc".substring(2, 1);
        } catch (StringIndexOutOfBoundsException e) {
            sb.append(e.getMessage()).append(';');
        }
        try {
            "abc".charAt(5);
        } catch (StringIndexOutOfBoundsException e) {
            sb.append(e.getMessage()).append(';');
        }
        try {
            new StringBuilder("ab").charAt(7);
        } catch (IndexOutOfBoundsException e) {
            sb.append(e.getClass().getName()).append(':').append(e.getMessage());
        }
        return sb.toString();
    }

    static String arraycopyMessages() {
        StringBuilder sb = new StringBuilder();
        int[] ints = new int[5];
        try {
            System.arraycopy(ints, 3, ints, 0, 4);
        } catch (ArrayIndexOutOfBoundsException e) {
            sb.append(e.getMessage()).append(';');
        }
        try {
            System.arraycopy(ints, -1, ints, 0, 1);
        } catch (ArrayIndexOutOfBoundsException e) {
            sb.append(e.getMessage()).append(';');
        }
        try {
            System.arraycopy(ints, 0, new long[5], 0, 1);
        } catch (ArrayStoreException e) {
            sb.append(e.getMessage()).append(';');
        }
        try {
            System.arraycopy("x", 0, ints, 0, 1);
        } catch (ArrayStoreException e) {
            sb.append(e.getMessage()).append(';');
        }
        try {
            Object[] mixed = {"a", 1};
            System.arraycopy(mixed, 0, new String[2], 0, 2);
        } catch (ArrayStoreException e) {
            sb.append(e.getMessage());
        }
        return sb.toString();
    }

    static final class Resource implements AutoCloseable {
        final StringBuilder log;
        final String name;
        final boolean failOnClose;

        Resource(StringBuilder log, String name, boolean failOnClose) {
            this.log = log;
            this.name = name;
            this.failOnClose = failOnClose;
            log.append("open ").append(name).append(';');
        }

        @Override
        public void close() {
            log.append("close ").append(name).append(';');
            if (failOnClose) {
                throw new IllegalStateException("close " + name);
            }
        }
    }

    static String tryWithResources() {
        StringBuilder log = new StringBuilder();
        try (Resource a = new Resource(log, "a", false); Resource b = new Resource(log, "b", true)) {
            log.append("body;");
            throw new IllegalArgumentException("body failed");
        } catch (IllegalArgumentException e) {
            log.append("caught ").append(e.getMessage()).append(" suppressed ").append(e.getSuppressed().length)
                    .append(' ').append(e.getSuppressed()[0].getMessage());
        }
        return log.toString();
    }

    static String exceptionToString() {
        return new IllegalStateException("x").toString() + "|" + new RuntimeException().toString() + "|"
                + new RuntimeException(new Custom("inner", 1)).getMessage();
    }

    static String exceptionFromLambdaAndInterface() {
        Runnable2 r = () -> {
            throw new Custom("from lambda", 3);
        };
        try {
            r.run();
            return "no";
        } catch (Custom e) {
            return e.getMessage();
        }
    }

    interface Runnable2 {
        void run();
    }

    static int exceptionInLoopKeepsLocals() {
        int sum = 0;
        long wide = 1L << 40;
        for (int i = 0; i < 10; i++) {
            try {
                if (i % 3 == 0) {
                    throw new Custom("skip", i);
                }
                sum += i;
            } catch (Custom e) {
                sum += 100 * e.code;
            }
        }
        return sum + (int) (wide >> 40);
    }

    static String initCauseRules() {
        RuntimeException e = new RuntimeException("x");
        e.initCause(new Custom("c", 1));
        try {
            e.initCause(null);
            return "no";
        } catch (IllegalStateException ise) {
            return ise.getMessage() + "|" + e.getCause().getMessage();
        }
    }
}
