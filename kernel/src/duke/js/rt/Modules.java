package duke.js.rt;

import java.util.ArrayList;

/**
 * Where compiled JavaScript modules sign up. Each generated module registers itself from its static
 * initializer, which dukec runs at boot because the package is a default include. The kernel then calls
 * {@link #runAll} once it is ready, so nothing in the kernel has to name a generated class. Modules run
 * in the order their classes are initialized, which is alphabetical by class name.
 */
public final class Modules {

    private static final ArrayList<Runnable> modules = new ArrayList<>();

    private Modules() {
    }

    public static void register(Runnable module) {
        modules.add(module);
    }

    /** Runs every registered module's top-level code, once. */
    public static void runAll() {
        ArrayList<Runnable> pending = new ArrayList<>(modules);
        modules.clear();
        for (Runnable module : pending) {
            module.run();
        }
    }
}
