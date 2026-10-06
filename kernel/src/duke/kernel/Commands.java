package duke.kernel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.Consumer;

/**
 * Shell commands registered at run time, next to the ones built into {@link Shell}. A handler gets the
 * words after the command name. Registering a name again replaces the handler.
 */
public final class Commands {

    private static final HashMap<String, Consumer<List<String>>> handlers = new HashMap<>();
    private static final ArrayList<String> names = new ArrayList<>();

    private Commands() {
    }

    public static void register(String name, Consumer<List<String>> handler) {
        if (!handlers.containsKey(name)) {
            names.add(name);
        }
        handlers.put(name, handler);
    }

    /** Runs the command {@code words[0]}; false if nothing is registered under that name. */
    static boolean run(List<String> words) {
        Consumer<List<String>> handler = handlers.get(words.get(0));
        if (handler == null) {
            return false;
        }
        List<String> args = new ArrayList<>();
        for (int i = 1; i < words.size(); i++) {
            args.add(words.get(i));
        }
        handler.accept(args);
        return true;
    }

    /** Registered names, in registration order. */
    static List<String> names() {
        return names;
    }
}
