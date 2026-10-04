import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.tools.ToolProvider;

/**
 * Prints one CSV row of project metrics for the checked-out commit ({@code --header} prints the
 * column names). Needs build/kernel.elf, build/kernel.map and compiler test results to exist.
 * Suite sizes count tests, not passes: main only moves through green CI, so the two are equal there.
 */
public class Metrics {

    static final String HEADER = "date,commit,conformance_tests,panic_tests,unit_tests,text_bytes,compiled_methods,kernel_loc,compiler_loc";

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--header")) {
            System.out.println(HEADER);
            return;
        }
        Path root = Path.of("").toAbsolutePath();
        List<String> row = List.of(
                git(root, "log", "-1", "--format=%cI"),
                git(root, "rev-parse", "--short", "HEAD"),
                Integer.toString(conformanceTests(root.resolve("tests/conformance"))),
                Integer.toString(panicTests(root.resolve("tests/panics"))),
                Integer.toString(unitTests(root.resolve("compiler/build/test-results/test"))),
                Long.toString(textBytes(root.resolve("build/kernel.elf"))),
                Long.toString(compiledMethods(root.resolve("build/kernel.map"))),
                Long.toString(linesOfJava(root.resolve("kernel/src"))),
                Long.toString(linesOfJava(root.resolve("compiler/src/main"))));
        System.out.println(String.join(",", row));
    }

    /** Same discovery rule as tools/Conformance.java: non-private, no-argument, static, non-void. */
    static int conformanceTests(Path dir) throws Exception {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        List<Path> sources = javaFiles(dir);
        Path out = Files.createTempDirectory("metrics-host");
        List<String> args = new ArrayList<>(List.of("-d", out.toString()));
        sources.forEach(p -> args.add(p.toString()));
        if (ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)) != 0) {
            throw new IllegalStateException("javac failed on " + dir);
        }
        int count = 0;
        try (URLClassLoader loader = new URLClassLoader(new URL[] {out.toUri().toURL()})) {
            for (Path source : sources) {
                String name = "duke.conformance." + source.getFileName().toString().replace(".java", "");
                for (Method m : loader.loadClass(name).getDeclaredMethods()) {
                    int mods = m.getModifiers();
                    if (Modifier.isStatic(mods) && !Modifier.isPrivate(mods) && m.getParameterCount() == 0
                            && m.getReturnType() != void.class && !m.isSynthetic()) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    static int panicTests(Path dir) throws IOException {
        return Files.isDirectory(dir) ? javaFiles(dir).size() : 0;
    }

    static int unitTests(Path results) throws IOException {
        if (!Files.isDirectory(results)) {
            return 0;
        }
        Pattern tests = Pattern.compile("<testsuite [^>]*tests=\"(\\d+)\"");
        int total = 0;
        try (Stream<Path> files = Files.list(results)) {
            for (Path xml : files.filter(p -> p.toString().endsWith(".xml")).toList()) {
                Matcher m = tests.matcher(Files.readString(xml));
                if (m.find()) {
                    total += Integer.parseInt(m.group(1));
                }
            }
        }
        return total;
    }

    /** Size of the .text section, read from the ELF section headers. */
    static long textBytes(Path elf) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(elf)).order(ByteOrder.LITTLE_ENDIAN);
        long shoff = b.getLong(40);
        int shentsize = b.getShort(58);
        int shnum = b.getShort(60);
        int shstrndx = b.getShort(62);
        long strtab = b.getLong((int) (shoff + (long) shstrndx * shentsize) + 24);
        for (int i = 0; i < shnum; i++) {
            int sh = (int) (shoff + (long) i * shentsize);
            int nameOffset = (int) (strtab + b.getInt(sh));
            int end = nameOffset;
            while (b.get(end) != 0) {
                end++;
            }
            String name = new String(b.array(), nameOffset, end - nameOffset, StandardCharsets.US_ASCII);
            if (name.equals(".text")) {
                return b.getLong(sh + 32);
            }
        }
        throw new IllegalStateException("no .text in " + elf);
    }

    static long compiledMethods(Path map) throws IOException {
        try (Stream<String> lines = Files.lines(map)) {
            return lines.filter(l -> l.contains("(")).count();
        }
    }

    static long linesOfJava(Path dir) throws IOException {
        long total = 0;
        for (Path p : javaFiles(dir)) {
            try (Stream<String> lines = Files.lines(p)) {
                total += lines.filter(l -> !l.isBlank()).count();
            }
        }
        return total;
    }

    static List<Path> javaFiles(Path dir) throws IOException {
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }

    static String git(Path root, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-C", root.toString()));
        command.addAll(List.of(args));
        Process p = new ProcessBuilder(command).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        if (p.waitFor() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed");
        }
        return out;
    }
}
