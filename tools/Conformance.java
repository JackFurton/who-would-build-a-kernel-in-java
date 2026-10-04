import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.tools.ToolProvider;

/**
 * Differential test of the compiler: runs every test method in tests/conformance on the host JVM,
 * then compiles the same classes into a kernel, boots it in QEMU and checks it prints identical
 * results. A test is any non-private static method with no parameters and a non-void result.
 *
 * <p>Run from the repository root with {@code make conformance}.
 */
public class Conformance {

    static final Path ROOT = Path.of("").toAbsolutePath();
    static final Path TESTS = ROOT.resolve("tests/conformance");
    static final Path OUT = ROOT.resolve("build/conformance");
    static final String PACKAGE = "duke.conformance";
    static final String DONE = "DUKE-CONFORMANCE-DONE";

    public static void main(String[] args) throws Exception {
        deleteRecursively(OUT);
        List<Path> testSources = javaFiles(TESTS);

        Path hostClasses = OUT.resolve("host");
        javac(List.of("-d", hostClasses.toString()), testSources);
        Map<String, Method> tests = new LinkedHashMap<>();
        Map<String, Long> expected = new LinkedHashMap<>();
        try (URLClassLoader loader = new URLClassLoader(new URL[] {hostClasses.toUri().toURL()})) {
            for (Path source : testSources) {
                String simpleName = source.getFileName().toString().replace(".java", "");
                Class<?> c = loader.loadClass(PACKAGE + "." + simpleName);
                Method[] methods = c.getDeclaredMethods();
                Arrays.sort(methods, Comparator.comparing(Method::getName));
                for (Method m : methods) {
                    int mods = m.getModifiers();
                    if (!Modifier.isStatic(mods) || Modifier.isPrivate(mods) || m.getParameterCount() != 0
                            || m.getReturnType() == void.class || m.isSynthetic()) {
                        continue;
                    }
                    m.setAccessible(true);
                    String name = simpleName + "." + m.getName();
                    tests.put(name, m);
                    expected.put(name, normalize(m.invoke(null)));
                }
            }
        }

        Path gen = OUT.resolve("gen");
        writeMain(gen, tests);

        Path kernelClasses = OUT.resolve("kclasses");
        List<Path> kernelSources = new ArrayList<>(javaFiles(ROOT.resolve("kernel/src")));
        kernelSources.addAll(testSources);
        kernelSources.addAll(javaFiles(gen));
        String sourcePath = "java.base=" + String.join(java.io.File.pathSeparator,
                ROOT.resolve("kernel/src").toString(), TESTS.toString(), gen.toString());
        javac(List.of("--system", "none", "--module-source-path", sourcePath, "-d", kernelClasses.toString()), kernelSources);

        Path elf = OUT.resolve("kernel.elf");
        run(OUT.resolve("dukec.txt"), ROOT.resolve("compiler/build/install/dukec/bin/dukec").toString(),
                "--classes", kernelClasses.toString(), "--entry", PACKAGE.replace('.', '/') + "/Main.main",
                "--output", elf.toString(), "--map", OUT.resolve("kernel.map").toString());
        run(null, "tools/make-esp.sh", OUT.resolve("esp").toString(), "build/limine/BOOTX64.EFI",
                "boot/limine.conf", elf.toString());
        Path serial = OUT.resolve("serial.log");
        int boot = runStatus(OUT.resolve("boot.txt"), "tools/boot-test.sh", OUT.resolve("esp").toString(),
                serial.toString(), DONE, "300");

        Map<String, Long> actual = parse(serial);
        List<String> failures = new ArrayList<>();
        for (Map.Entry<String, Long> e : expected.entrySet()) {
            Long got = actual.get(e.getKey());
            if (got == null) {
                failures.add(e.getKey() + ": no result (kernel stopped before reaching it)");
            } else if (!got.equals(e.getValue())) {
                failures.add(e.getKey() + ": expected " + e.getValue() + ", got " + got);
            }
        }

        int passed = expected.size() - failures.size();
        failures.forEach(f -> System.out.println("FAIL " + f));
        if (boot != 0) {
            System.out.println(Files.readString(OUT.resolve("boot.txt")));
        }
        String summary = "conformance: " + passed + "/" + expected.size() + " passed";
        System.out.println(summary);
        writeStepSummary(summary, failures);
        System.exit(failures.isEmpty() && boot == 0 ? 0 : 1);
    }

    static long normalize(Object value) {
        return switch (value) {
            case Boolean b -> b ? 1 : 0;
            case Character c -> c;
            case Number n -> n.longValue();
            default -> throw new IllegalArgumentException("unsupported test result type " + value.getClass());
        };
    }

    static void writeMain(Path gen, Map<String, Method> tests) throws IOException {
        StringBuilder src = new StringBuilder();
        src.append("package ").append(PACKAGE).append(";\n\n");
        src.append("import duke.kernel.Console;\nimport duke.kernel.Serial;\n\n");
        src.append("final class Main {\n    static void main() {\n        Serial.init();\n");
        for (Map.Entry<String, Method> e : tests.entrySet()) {
            String call = e.getKey() + "()";
            String value = e.getValue().getReturnType() == boolean.class ? "(" + call + " ? 1L : 0L)" : "(long) " + call;
            src.append("        Console.print(\"").append(e.getKey()).append("=\");\n");
            src.append("        Console.print(").append(value).append(");\n");
            src.append("        Console.println(\"\");\n");
        }
        src.append("        Console.println(\"").append(DONE).append("\");\n    }\n}\n");
        Path file = gen.resolve(PACKAGE.replace('.', '/')).resolve("Main.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, src);
    }

    static Map<String, Long> parse(Path serial) throws IOException {
        Map<String, Long> results = new LinkedHashMap<>();
        if (!Files.exists(serial)) {
            return results;
        }
        Pattern line = Pattern.compile("([A-Za-z0-9_]+\\.[A-Za-z0-9_]+)=(-?\\d+)");
        for (String l : Files.readString(serial).split("\r?\n")) {
            // Limine leaves terminal escape sequences in front of the first line.
            Matcher m = line.matcher(l.replaceAll("\\x1b\\[[0-9;=?]*[A-Za-z]", "").strip());
            if (m.matches()) {
                results.put(m.group(1), Long.parseLong(m.group(2)));
            }
        }
        return results;
    }

    static void writeStepSummary(String summary, List<String> failures) throws IOException {
        String path = System.getenv("GITHUB_STEP_SUMMARY");
        if (path == null) {
            return;
        }
        StringBuilder md = new StringBuilder("### " + summary + "\n");
        for (String f : failures) {
            md.append("- ").append(f).append('\n');
        }
        Files.writeString(Path.of(path), md, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
    }

    static void javac(List<String> options, List<Path> sources) {
        List<String> args = new ArrayList<>(options);
        sources.forEach(p -> args.add(p.toString()));
        int status = ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new));
        if (status != 0) {
            throw new IllegalStateException("javac failed");
        }
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
