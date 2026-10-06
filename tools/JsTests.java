import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Differential test of jsc: runs every program in tests/js through both back ends and checks the
 * output and exit status match what node prints for the same source.
 *
 * <ul>
 *   <li>{@code x86}: the program as a standalone x86-64 Linux executable. On any other host it runs
 *       in an amd64 Docker container.
 *   <li>{@code java}: the program translated to Java, as it is for the kernel, compiled with the
 *       kernel's JavaScript runtime and run on this JVM.
 * </ul>
 *
 * <p>Run from the repository root with {@code make js-test}, or name tests to run only those.
 */
public class JsTests {

    static final Path ROOT = Path.of("").toAbsolutePath();
    static final Path TESTS = ROOT.resolve("tests/js");
    static final Path OUT = ROOT.resolve("build/js-tests");
    static final Path JSC = ROOT.resolve("compiler/build/install/dukec/bin/jsc");

    /** Which back ends to run, from JS_BACKENDS (default both): handy without Docker, which x86 needs off Linux. */
    static final String BACKENDS = System.getenv().getOrDefault("JS_BACKENDS", "x86,java");

    record Result(String output, int exit) {}

    public static void main(String[] args) throws Exception {
        Files.createDirectories(OUT);
        List<Path> programs;
        try (Stream<Path> files = Files.list(TESTS)) {
            programs = files.filter(p -> p.toString().endsWith(".js")).sorted().toList();
        }
        List<String> wanted = List.of(args);
        int failures = 0;
        int ran = 0;
        for (Path program : programs) {
            String name = program.getFileName().toString().replaceAll("\\.js$", "");
            if (!wanted.isEmpty() && !wanted.contains(name)) {
                continue;
            }
            ran++;
            Result expected = run(List.of("node", program.toString()));
            // A program whose first line is "// backends: java" uses features only the Java back end has.
            boolean javaOnly = Files.readAllLines(program).get(0).equals("// backends: java");
            if (BACKENDS.contains("x86") && !javaOnly) {
                failures += check(name + " (x86)", expected, () -> runX86(program, name));
            }
            if (BACKENDS.contains("java")) {
                failures += check(name + " (java)", expected, () -> runJava(program, name));
            }
        }
        if (ran == 0) {
            System.out.println("no tests matched");
            System.exit(1);
        }
        System.out.println(failures == 0 ? "all " + ran + " programs passed (" + BACKENDS + ")" : failures + " failures");
        System.exit(failures == 0 ? 0 : 1);
    }

    interface Run {
        Result run() throws Exception;
    }

    /** Compares one run with node's, printing the verdict; returns the number of failures (0 or 1). */
    static int check(String label, Result expected, Run run) throws Exception {
        Result actual = run.run();
        if (expected.output().equals(actual.output()) && expected.exit() == actual.exit()) {
            System.out.println("PASS " + label);
            return 0;
        }
        System.out.println("FAIL " + label + " (exit " + actual.exit() + ", node " + expected.exit() + ")");
        System.out.println(firstDifference(expected.output(), actual.output()));
        return 1;
    }

    static Result runX86(Path program, String name) throws Exception {
        Path exe = OUT.resolve(name);
        Result compiled = run(List.of(JSC.toString(), program.toString(), "--output", exe.toString()));
        return compiled.exit() != 0 ? compiled : run(executable(exe));
    }

    /** The module class generated for {@code name.js}: first letter upper-cased, as jsc does. */
    static String moduleClass(String name) {
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    static final String HOST_MAIN = """
            import duke.js.rt.Globals;
            import duke.js.rt.JsError;

            public class JsHostMain {
                public static void main(String[] args) throws Exception {
                    Globals.setSink(s -> System.out.print(s));
                    try {
                        Class.forName("duke.js.gen." + args[0]).getMethod("run").invoke(null);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        System.out.println(e.getCause());
                        System.exit(1);
                    }
                }
            }
            """;

    static Result runJava(Path program, String name) throws Exception {
        Path dir = OUT.resolve(name + "-java");
        Path generated = dir.resolve("src");
        Path classes = dir.resolve("classes");
        Files.createDirectories(classes);
        Result translated = run(List.of(JSC.toString(), "--java", generated.toString(), program.toString()));
        if (translated.exit() != 0) {
            return translated;
        }
        Path host = dir.resolve("JsHostMain.java");
        Files.writeString(host, HOST_MAIN);
        List<String> javac = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin/javac").toString(),
                "-nowarn", "-d", classes.toString(), host.toString(),
                generated.resolve("duke/js/gen/" + moduleClass(name) + ".java").toString()));
        try (Stream<Path> runtime = Files.list(ROOT.resolve("kernel/src/duke/js/rt"))) {
            runtime.map(Path::toString).forEach(javac::add);
        }
        Result compiled = run(javac);
        if (compiled.exit() != 0) {
            return compiled;
        }
        return run(List.of(Path.of(System.getProperty("java.home"), "bin/java").toString(), "-Xss64m", "-cp",
                classes.toString(), "JsHostMain", moduleClass(name)));
    }

    static List<String> executable(Path exe) {
        boolean native64 = System.getProperty("os.name").equals("Linux")
                && System.getProperty("os.arch").matches("amd64|x86_64");
        if (native64) {
            return List.of(exe.toString());
        }
        return List.of("docker", "run", "-q", "--rm", "--platform", "linux/amd64", "-v", OUT + ":/t", "alpine",
                "/t/" + exe.getFileName());
    }

    static Result run(List<String> command) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        byte[] bytes = p.getInputStream().readAllBytes();
        if (!p.waitFor(60, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            return new Result("timed out", -1);
        }
        return new Result(new String(bytes), p.exitValue());
    }

    static String firstDifference(String expected, String actual) {
        String[] e = expected.split("\n", -1);
        String[] a = actual.split("\n", -1);
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < Math.max(e.length, a.length); i++) {
            String want = i < e.length ? e[i] : "<missing>";
            String got = i < a.length ? a[i] : "<missing>";
            if (!want.equals(got)) {
                lines.add("  line " + (i + 1) + "\n    node: " + want + "\n    jsc:  " + got);
                if (lines.size() == 3) {
                    break;
                }
            }
        }
        return String.join("\n", lines);
    }
}
