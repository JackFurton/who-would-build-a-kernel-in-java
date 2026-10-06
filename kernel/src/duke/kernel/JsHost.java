package duke.kernel;

import duke.js.rt.Globals;
import duke.js.rt.JS;
import duke.js.rt.JsArray;
import duke.js.rt.JsError;
import duke.js.rt.JsFunction;
import duke.js.rt.JsObject;
import duke.js.rt.Modules;
import duke.kernel.mm.PhysicalMemory;
import duke.kernel.time.Timer;
import duke.rt.Heap;

/**
 * What JavaScript modules can see of the kernel: the {@code Kernel} global, and the shell commands
 * they register with {@code Kernel.command(name, fn)}. Kept small and explicit on purpose, so
 * everything JS can do to the machine is listed here. Only reached from generated code, so a kernel
 * built without JavaScript modules doesn't contain it or the JS runtime.
 */
public final class JsHost {

    private JsHost() {
    }

    /** Starts JavaScript: sets up console.log and {@code Kernel}, then runs every compiled module. */
    public static void start() {
        install();
        Modules.runAll();
    }

    private static void install() {
        Globals.setSink(s -> Console.print(s));
        JsObject kernel = new JsObject();
        kernel.set("print", Globals.function("print", (callee, self, args) -> {
            Console.print(JS.str(JS.arg(args, 0)));
            return null;
        }));
        kernel.set("println", Globals.function("println", (callee, self, args) -> {
            Console.println(JS.str(JS.arg(args, 0)));
            return null;
        }));
        kernel.set("uptimeMs", Globals.function("uptimeMs", (callee, self, args) -> Long.valueOf(Timer.uptimeMillis())));
        kernel.set("ticks", Globals.function("ticks", (callee, self, args) -> Long.valueOf(Timer.ticks())));
        kernel.set("freeFrames", Globals.function("freeFrames", (callee, self, args) -> Long.valueOf(PhysicalMemory.freeFrames())));
        kernel.set("heapUsed", Globals.function("heapUsed", (callee, self, args) -> Long.valueOf(Heap.used())));
        kernel.set("heapCommitted", Globals.function("heapCommitted", (callee, self, args) -> Long.valueOf(Heap.committed())));
        kernel.set("collections", Globals.function("collections", (callee, self, args) -> Long.valueOf(Heap.collections())));
        kernel.set("command", Globals.function("command", (callee, self, args) -> {
            Object name = JS.arg(args, 0);
            Object body = JS.arg(args, 1);
            if (!(name instanceof String) || !(body instanceof JsFunction)) {
                throw new JsError("TypeError: Kernel.command(name, function) needs a string and a function");
            }
            JsFunction handler = (JsFunction) body;
            Commands.register((String) name, words -> {
                JsArray list = new JsArray();
                for (String word : words) {
                    list.add(word);
                }
                handler.call(null, new Object[] {list});
            });
            return null;
        }));
        Globals.define("Kernel", kernel);
    }
}
