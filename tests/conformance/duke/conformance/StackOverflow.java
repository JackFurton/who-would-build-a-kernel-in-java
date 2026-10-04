package duke.conformance;

/** Depths differ between HotSpot and Duke, so tests only compare whether and how overflow surfaces. */
final class StackOverflow {

    private static int recurse(int n) {
        return recurse(n + 1) + 1;
    }

    static String overflowIsCatchable() {
        try {
            recurse(0);
            return "no";
        } catch (StackOverflowError e) {
            return e.getClass().getName() + " " + e.getMessage();
        }
    }

    // A second overflow only works if catching the first restored the limit.
    static int recoversForAnotherOverflow() {
        int caught = 0;
        for (int i = 0; i < 3; i++) {
            try {
                recurse(0);
            } catch (StackOverflowError e) {
                caught++;
            }
        }
        return caught;
    }

    static boolean caughtAsError() {
        try {
            recurse(0);
            return false;
        } catch (Error e) {
            return e instanceof VirtualMachineError;
        }
    }

    static int normalCallsStillWorkAfterward() {
        try {
            recurse(0);
        } catch (StackOverflowError e) {
            // fall through
        }
        return sumTo(200);
    }

    private static int sumTo(int n) {
        return n == 0 ? 0 : n + sumTo(n - 1);
    }
}
