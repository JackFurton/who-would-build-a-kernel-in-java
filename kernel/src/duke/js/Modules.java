package duke.js;

/**
 * Runs the JavaScript modules. This empty version is what builds that don't translate any JavaScript
 * (the test harnesses) compile. The kernel build replaces it with the class jsc generates, which calls
 * each module in kernel/js, so Kernel can call this without naming generated code.
 */
public final class Modules {

    private Modules() {
    }

    public static void init() {
    }
}
