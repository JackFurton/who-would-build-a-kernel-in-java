package duke.js.rt;

/**
 * Walks an iterable the way a {@code for...of} loop does: arrays and strings directly, anything else through the
 * {@code Symbol.iterator} protocol.
 */
public abstract class JsIter {

    /** Advances to the next value; false when there are no more. */
    public abstract boolean next();

    /** The value {@link #next} stopped at. */
    public abstract Object value();

    /** Called when a loop leaves before the end, so an iterator that cares (a generator) can clean up. */
    public void close() {
    }

    /** Arrays are read by index, so elements added during the loop are visited, as in JavaScript. */
    static final class ArrayIter extends JsIter {
        private final JsArray array;
        private int index = -1;

        ArrayIter(JsArray array) {
            this.array = array;
        }

        @Override
        public boolean next() {
            return ++index < array.length();
        }

        @Override
        public Object value() {
            return array.get(index);
        }
    }

    static final class StringIter extends JsIter {
        private final String s;
        private int index = -1;

        StringIter(String s) {
            this.s = s;
        }

        @Override
        public boolean next() {
            return ++index < s.length();
        }

        @Override
        public Object value() {
            return s.substring(index, index + 1);
        }
    }

    /** An object with a {@code next()} method, called until it reports done. */
    static final class ProtocolIter extends JsIter {
        private final Object iterator;
        private final Object nextMethod;
        private Object current;
        private boolean done;

        ProtocolIter(Object iterator) {
            this.iterator = iterator;
            this.nextMethod = JS.get(iterator, "next");
        }

        @Override
        public boolean next() {
            if (done) {
                return false;
            }
            Object result = JS.callWith(nextMethod, iterator, new Object[0]);
            if (!(result instanceof JsObject)) {
                throw new JsError("TypeError: Iterator result " + JS.str(result) + " is not an object");
            }
            if (JS.truthy(JS.get(result, "done"))) {
                done = true;
                return false;
            }
            current = JS.get(result, "value");
            return true;
        }

        @Override
        public Object value() {
            return current;
        }

        @Override
        public void close() {
            if (!done) {
                done = true;
                Object ret = JS.get(iterator, "return");
                if (ret instanceof JsFunction) {
                    ((JsFunction) ret).call(iterator, new Object[0]);
                }
            }
        }
    }
}
