import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Boots one kernel per file in tests/panics and checks it dies with the panic line named in the
 * file's first-line comment ({@code // expect: PANIC: ...}). Covers the runtime checks the
 * conformance suite can't reach, because there a failed check stops the whole run.
 *
 * <p>Run from the repository root with {@code make panic-tests}.
 */
public class PanicTests {

    static final Path TESTS = Harness.ROOT.resolve("tests/panics");
    static final Path OUT = Harness.ROOT.resolve("build/panics");
    static final int TIMEOUT_SECONDS = 120;

    record Case(String name, String expected) {}

    record Result(Case test, boolean passed, String output) {}

    public static void main(String[] args) throws Exception {
        Harness.deleteRecursively(OUT);
        List<Case> cases = new ArrayList<>();
        for (Path source : Harness.javaFiles(TESTS)) {
            String first = Files.readAllLines(source).getFirst();
            if (!first.startsWith("// expect: ")) {
                throw new IllegalStateException(source + " must start with // expect: <panic line>");
            }
            cases.add(new Case(source.getFileName().toString().replace(".java", ""), first.substring("// expect: ".length())));
        }

        Path classes = OUT.resolve("classes");
        Harness.compileKernel(classes, List.of(TESTS));

        List<Future<Result>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors() / 2))) {
            for (Case c : cases) {
                futures.add(pool.submit(() -> boot(classes, c)));
            }
        }

        int passed = 0;
        for (Future<Result> f : futures) {
            Result r = f.get();
            if (r.passed()) {
                passed++;
                System.out.println("ok   " + r.test().name());
            } else {
                System.out.println("FAIL " + r.test().name() + ": expected \"" + r.test().expected() + "\"");
                System.out.println(r.output().indent(4));
            }
        }
        System.out.println("panic-tests: " + passed + "/" + cases.size() + " passed");
        System.exit(passed == cases.size() ? 0 : 1);
    }

    static Result boot(Path classes, Case c) throws Exception {
        Path dir = OUT.resolve(c.name());
        Path esp = Harness.buildImage(classes, "duke/panics/" + c.name() + ".main", dir);
        Path serial = dir.resolve("serial.log");
        Process qemu = new ProcessBuilder("tools/qemu.sh", esp.toString(), "-serial", "file:" + serial)
                .directory(Harness.ROOT.toFile())
                .redirectErrorStream(true)
                .redirectOutput(dir.resolve("qemu.txt").toFile())
                .start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
            while (System.nanoTime() < deadline) {
                String output = Harness.readSerial(serial);
                // Wait for the full line: the panic message is written a character at a time.
                for (String line : output.split("\n", -1)) {
                    if (line.equals(c.expected()) && output.contains(c.expected() + "\n")) {
                        return new Result(c, true, output);
                    }
                }
                if (!qemu.isAlive()) {
                    break;
                }
                Thread.sleep(100);
            }
            return new Result(c, false, Harness.readSerial(serial));
        } finally {
            qemu.destroy();
            qemu.waitFor();
        }
    }
}
