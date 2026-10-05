package duke.js;

import duke.compiler.image.ElfWriter;
import duke.compiler.image.Image;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** {@code jsc program.js --output program}: compiles JavaScript to a static x86-64 Linux executable. */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws IOException {
        Path input = null;
        Path output = null;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--output") || args[i].equals("-o")) {
                if (++i == args.length) {
                    usage("--output needs a value");
                }
                output = Path.of(args[i]);
            } else if (input == null && !args[i].startsWith("-")) {
                input = Path.of(args[i]);
            } else {
                usage("unknown argument " + args[i]);
            }
        }
        if (input == null || output == null) {
            usage("an input file and --output are required");
        }
        byte[] elf;
        try {
            elf = compile(input.toString(), Files.readString(input));
        } catch (JsException e) {
            System.err.println("jsc: error: " + e.getMessage());
            System.exit(1);
            return;
        }
        Files.write(output, elf);
        if (!output.toFile().setExecutable(true)) {
            System.err.println("jsc: warning: could not mark " + output + " executable");
        }
    }

    /** Compiles {@code source} (named {@code file} in errors) together with the runtime prelude. */
    public static byte[] compile(String file, String source) throws IOException {
        List<Node.Stmt> prelude = new Parser("<prelude>", prelude()).parseProgram();
        List<Node.Stmt> program = new Parser(file, source).parseProgram();
        Analyzer analyzer = new Analyzer(file, prelude, program);
        Image image = new CodeGen(file, analyzer).generate(prelude, program);
        // Text starts one page in, so the headers' page lands exactly at BASE.
        Image.Linked linked = image.link(CodeGen.BASE + Image.PAGE);
        return ElfWriter.write(linked, linked.address("_start"), true);
    }

    private static String prelude() throws IOException {
        try (InputStream in = Main.class.getResourceAsStream("prelude.js")) {
            if (in == null) {
                throw new IOException("prelude.js is missing from the jsc jar");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void usage(String problem) {
        System.err.println("jsc: " + problem);
        System.err.println("usage: jsc program.js --output program");
        System.exit(2);
    }
}
