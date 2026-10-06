package duke.kernel;

import java.util.ArrayList;

/**
 * Work to do once the kernel is up, just before the shell starts. Code nothing else refers to can sign
 * up from a static initializer: dukec runs those at boot for classes in an included package, which is
 * how generated code reaches the kernel without the kernel naming it.
 */
public final class Startup {

    private static final ArrayList<Runnable> hooks = new ArrayList<>();

    private Startup() {
    }

    public static void register(Runnable hook) {
        hooks.add(hook);
    }

    /** Runs every registered hook, once, in the order they signed up. */
    static void runAll() {
        ArrayList<Runnable> pending = new ArrayList<>(hooks);
        hooks.clear();
        for (Runnable hook : pending) {
            hook.run();
        }
    }
}
