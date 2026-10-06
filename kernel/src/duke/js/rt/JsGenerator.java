package duke.js.rt;

/**
 * A running generator: the resume function jsc builds from the generator's body, and what the protocol needs
 * around it. The resume function is called with a mode (0 next, 1 throw, 2 return) and a value; it returns a
 * {@link Yielded} when the body yields, and anything else when the body finishes.
 */
final class JsGenerator {

    /** What the resume function returns for a yield. */
    static final class Yielded {
        final Object value;

        Yielded(Object value) {
            this.value = value;
        }
    }

    private final JsFunction resume;
    private boolean started;
    private boolean done;
    private boolean running;

    JsGenerator(JsFunction resume) {
        this.resume = resume;
    }

    static JsObject result(Object value, boolean done) {
        JsObject r = (JsObject) JS.object();
        r.set("value", value);
        r.set("done", JS.bool(done));
        return r;
    }

    Object step(int mode, Object arg) {
        if (running) {
            throw new JsError("TypeError: Generator is already running");
        }
        if (!started && mode != 0) {
            // Throwing into or returning from a generator that never ran finishes it without running its body.
            done = true;
        }
        if (done) {
            if (mode == 1) {
                throw new JsThrow(arg);
            }
            return result(mode == 2 ? arg : null, true);
        }
        started = true;
        running = true;
        Object outcome;
        try {
            outcome = resume.call(null, new Object[] {Long.valueOf(mode), arg});
        } catch (RuntimeException | StackOverflowError e) {
            done = true;
            throw e;
        } finally {
            running = false;
        }
        if (outcome instanceof Yielded) {
            return result(((Yielded) outcome).value, false);
        }
        done = true;
        return result(outcome, true);
    }
}
