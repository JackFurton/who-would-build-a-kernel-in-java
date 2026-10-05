import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Differential test of jsc: compiles every program in tests/js, runs the executable, and checks
 * its output and exit status match what node prints for the same source.
 *
 * <p>The executables are x86-64 Linux. On any other host they run in an amd64 Docker container.
 * Run from the repository root with {@code make js-test}, or name tests to run only those.
 */
public class JsTests {

    static final Path ROOT = Path.of("").toAbsolutePath();
    static final Path TESTS = ROOT.resolve("tests/js");
    static final Path OUT = ROOT.resolve("build/js-tests");
    static final Path JSC = ROOT.resolve("compiler/build/install/dukec/bin/jsc");

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
            Path exe = OUT.resolve(name);
            Result compiled = run(List.of(JSC.toString(), program.toString(), "--output", exe.toString()));
            if (compiled.exit() != 0) {
                System.out.println("FAIL " + name + ": jsc failed\n" + compiled.output());
                failures++;
                continue;
            }
            Result expected = run(List.of("node", program.toString()));
            Result actual = run(executable(exe));
            if (expected.output().equals(actual.output()) && expected.exit() == actual.exit()) {
                System.out.println("PASS " + name);
            } else {
                failures++;
                System.out.println("FAIL " + name + " (exit " + actual.exit() + ", node " + expected.exit() + ")");
                System.out.println(firstDifference(expected.output(), actual.output()));
            }
        }
        if (ran == 0) {
            System.out.println("no tests matched");
            System.exit(1);
        }
        System.out.println(ran - failures + " of " + ran + " passed");
        System.exit(failures == 0 ? 0 : 1);
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
