package duke.js;

/**
 * Runs the JavaScript modules. This empty version is what builds that don't translate any JavaScript
 * (the test harnesses) compile. The kernel build replaces it with the class jsc generates, which calls
 * each module in kernel/js, so Kernel can call this without naming generated code.
 *
 * <p>Shadowing a source file at build time works, but it is a trick: editing this file changes nothing
 * in the kernel image. A cleaner way, if more generated code turns up, is to let dukec take extra root
 * classes (say {@code --include duke/js/gen/Modules}) or read a registry the generated code adds to.
 * Then the generated class would only add to the build, and this stub and the Makefile's
 * {@code filter-out} could go.
 */
public final class Modules {

    private Modules() {
    }

    public static void init() {
    }
}
