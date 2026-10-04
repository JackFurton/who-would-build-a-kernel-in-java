import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import javax.tools.ToolProvider;

/** Shared steps for the test harnesses in this directory: compile, link, lay out an ESP, boot. */
final class Harness {

    static final Path ROOT = Path.of("").toAbsolutePath();
    static final Path KERNEL_SOURCES = ROOT.resolve("kernel/src");

    private Harness() {
    }

    /** Compiles the kernel plus extra source roots as one java.base module. */
    static void compileKernel(Path out, List<Path> extraRoots) throws IOException {
        List<Path> roots = new ArrayList<>(List.of(KERNEL_SOURCES));
        roots.addAll(extraRoots);
        List<Path> sources = new ArrayList<>();
        for (Path root : roots) {
            sources.addAll(javaFiles(root));
        }
        String sourcePath = "java.base=" + String.join(File.pathSeparator, roots.stream().map(Path::toString).toList());
        javac(List.of("--system", "none", "--module-source-path", sourcePath, "-d", out.toString()), sources);
    }

    static void javac(List<String> options, List<Path> sources) {
        List<String> args = new ArrayList<>(options);
        sources.forEach(p -> args.add(p.toString()));
        int status = ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new));
        if (status != 0) {
            throw new IllegalStateException("javac failed");
        }
    }

    /** Runs dukec and lays the result out as a bootable ESP directory under {@code dir}. */
    static Path buildImage(Path classes, String entry, Path dir) throws Exception {
        Path elf = dir.resolve("kernel.elf");
        run(dir.resolve("dukec.txt"), ROOT.resolve("compiler/build/install/dukec/bin/dukec").toString(),
                "--classes", classes.toString(), "--entry", entry,
                "--output", elf.toString(), "--map", dir.resolve("kernel.map").toString());
        Path esp = dir.resolve("esp");
        run(null, "tools/make-esp.sh", esp.toString(), "build/limine/BOOTX64.EFI", "boot/limine.conf", elf.toString());
        return esp;
    }

    static void run(Path log, String... command) throws Exception {
        if (runStatus(log, command) != 0) {
            if (log != null) {
                System.out.print(Files.readString(log));
            }
            throw new IllegalStateException("command failed: " + String.join(" ", command));
        }
    }

    static int runStatus(Path log, String... command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command).directory(ROOT.toFile()).redirectErrorStream(true);
        if (log != null) {
            Files.createDirectories(log.getParent());
            pb.redirectOutput(log.toFile());
        } else {
            pb.inheritIO();
        }
        return pb.start().waitFor();
    }

    /** Serial output with the terminal escape sequences Limine leaves behind removed. */
    static String readSerial(Path log) throws IOException {
        if (!Files.exists(log)) {
            return "";
        }
        return Files.readString(log).replaceAll("\\x1b\\[[0-9;=?]*[A-Za-z]", "").replace("\r", "");
    }

    static List<Path> javaFiles(Path dir) throws IOException {
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }

    static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path p : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
