package duke.kernel;

import duke.js.rt.Globals;
import duke.js.rt.JS;
import duke.js.rt.JsArray;
import duke.js.rt.JsError;
import duke.js.rt.JsFunction;
import duke.js.rt.JsObject;
import duke.kernel.mm.PhysicalMemory;
import duke.kernel.time.Timer;
import duke.rt.Heap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * What JavaScript modules can see of the kernel: the {@code Kernel} global, and the shell commands
 * they register with {@code Kernel.command(name, fn)}. Kept small and explicit on purpose, so
 * everything JS can do to the machine is listed here.
 */
public final class JsHost {

    private static final HashMap<String, JsFunction> commands = new HashMap<>();
    private static final ArrayList<String> names = new ArrayList<>();

    private JsHost() {
    }

    /** Points console.log at the console and defines {@code Kernel}. Call before running any module. */
    public static void install() {
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
            if (!commands.containsKey(name)) {
                names.add((String) name);
            }
            commands.put((String) name, (JsFunction) body);
            return null;
        }));
        Globals.define("Kernel", kernel);
    }

    /** Runs the JS command called {@code name} with the words after it; false if there is none. */
    static boolean run(String name, List<String> words) {
        JsFunction command = commands.get(name);
        if (command == null) {
            return false;
        }
        JsArray args = new JsArray();
        for (int i = 1; i < words.size(); i++) {
            args.add(words.get(i));
        }
        command.call(null, new Object[] {args});
        return true;
    }

    static List<String> commandNames() {
        return names;
    }
}
