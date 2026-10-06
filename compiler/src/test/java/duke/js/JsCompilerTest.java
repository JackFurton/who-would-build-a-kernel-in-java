package duke.js;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Compile-time behaviour of jsc. What the programs print is covered by tests/js (make js-test). */
class JsCompilerTest {

    private static String error(String source) {
        return assertThrows(JsException.class, () -> Main.compile("t.js", source)).getMessage();
    }

    @Test
    void producesAnElfExecutable() throws IOException {
        byte[] elf = Main.compile("t.js", "console.log(1 + 2);");
        assertEquals(0x7F, elf[0]);
        assertEquals('E', elf[1]);
        assertEquals('L', elf[2]);
        assertEquals('F', elf[3]);
        assertEquals(2, elf[16]); // ET_EXEC
        assertEquals(62, elf[18]); // EM_X86_64
    }

    @Test
    void reportsUndefinedNamesWithTheirLine() {
        assertEquals("t.js:3: nope is not defined", error("var a = 1;\n\nconsole.log(nope);"));
    }

    @Test
    void rejectsAssignmentToConst() {
        assertTrue(error("const x = 1;\nx = 2;").contains("Assignment to constant variable 'x'"));
        assertTrue(error("const x = 1;\nx++;").contains("Assignment to constant variable 'x'"));
    }

    @Test
    void rejectsRedeclaredLet() {
        assertEquals("t.js:2: Identifier 'a' has already been declared", error("let a = 1;\nlet a = 2;"));
    }

    static Stream<Arguments> unsupported() {
        return Stream.of(
                Arguments.of("var x = 1.5;", "floating-point numbers are not supported"),
                Arguments.of("class A {}", "'class' is not supported yet"),
                Arguments.of("try { }", "missing catch or finally after try"),
                Arguments.of("throw\n1;", "a line break is not allowed after 'throw'"),
                Arguments.of("var s = \"\u00e9\";", "only ASCII strings"),
                Arguments.of("var x = 1 +;", "unexpected ';'"),
                Arguments.of("break;", "'break' outside a loop"),
                Arguments.of("var a = [...b];", "spread is not supported yet"));
    }

    @ParameterizedTest
    @MethodSource("unsupported")
    void explainsUnsupportedSyntax(String source, String message) {
        assertTrue(error(source).contains(message), () -> error(source));
    }

    @Test
    void x86BackEndRefusesWhatOnlyTheJavaBackEndHas() {
        assertTrue(error("function F() {}\nnew F();").contains("'new' is not supported by the x86 back end"));
        assertTrue(error("for (var k in {}) {}").contains("'for...in' is not supported by the x86 back end"));
        assertTrue(error("var a = 1 instanceof Object;").contains("unsupported operator instanceof"));
        assertTrue(error("throw 1;").contains("'throw' is not supported by the x86 back end"));
        assertTrue(error("try { } finally { }").contains("'try' is not supported by the x86 back end"));
    }

    @Test
    void compilesClosuresAndTemplates() throws IOException {
        Main.compile("t.js", """
                function make() { let n = 0; return () => `${++n}`; }
                const f = make();
                for (let i = 0; i < 3; i++) console.log(f(), [i, { i }]);
                """);
    }

    // ---- the Java back end, which is how JavaScript gets into the kernel ----

    @Test
    void translatesEachModuleToAClassThatRegistersItself() {
        Map<String, String> modules = new LinkedHashMap<>();
        modules.put("kernel/js/commands.js", "console.log(1);");
        modules.put("kernel/js/fs-tools.js", "var x = 2;");
        Map<String, String> files = Main.translateAll(modules);
        assertEquals(List.of("duke/js/gen/Commands.java", "duke/js/gen/FsTools.java", "duke/js/gen/JsStartup.java"),
                List.copyOf(files.keySet()));
        assertTrue(files.get("duke/js/gen/Commands.java").contains("Modules.register(() -> run());"));
        assertTrue(files.get("duke/js/gen/JsStartup.java").contains("Startup.register(() -> JsHost.start());"));
    }

    @Test
    void generatesNothingWithoutModules() {
        assertEquals(Map.of(), Main.translateAll(Map.of()));
    }

    @Test
    void rejectsModulesThatCollideAfterNaming() {
        Map<String, String> modules = new LinkedHashMap<>();
        modules.put("a/fs-tools.js", "");
        modules.put("b/fsTools.js", "");
        assertEquals("b/fsTools.js:1: another module is already called FsTools",
                assertThrows(JsException.class, () -> Main.translateAll(modules)).getMessage());
    }

    @Test
    void javaBackendRejectsWhatTheOtherBackendDoes() {
        assertTrue(assertThrows(JsException.class, () -> Main.translate("t.js", "const a = 1; a = 2;", "T"))
                .getMessage().contains("Assignment to constant variable 'a'"));
        assertTrue(assertThrows(JsException.class, () -> Main.translate("t.js", "nope();", "T"))
                .getMessage().contains("nope is not defined"));
    }

    @Test
    void generatedJavaCompilesAgainstTheKernelRuntime() throws IOException {
        // Every construct the translator handles, so a change that emits invalid Java fails here
        // instead of in a kernel build.
        String source = """
                const answer = 42;
                let counter = 0;
                var items = [1, 2, 3], o = { a: 1, "b-c": [answer] };
                function twice(f, x) { return f(f(x)); }
                function early(n) { if (n > 1) return "big"; else return "small"; console.log("dead"); }
                const bump = () => counter++;
                for (let i = 0; i < 3; i++) { bump(); if (i == 1) continue; items.push(() => i); }
                for (const x of items) { if (x === 3) break; }
                while (counter < 10) counter += 2;
                do { counter--; } while (counter > 8);
                o.a += 1; o["z"] = o.a++ + --o.a;
                counter ||= 5; counter ??= 6; counter &&= 7;
                var t = `${answer}-${counter}`, u = typeof t === "string" ? -answer : ~answer;
                var nested = function self(n) { return n ? self(n - 1) : this; };
                function Point(x) { this.x = x; }
                var p = new Point(1), q = new Point;
                var has = "x" in p && p instanceof Point && delete p.x && void 0 === undefined;
                for (var key in o) { if (key === "a") continue; }
                for (let k2 in items) items[k2]++;
                try { throw new Error("x"); } catch (e) { has = e; } finally { has = null; }
                try { has = 1; } catch { has = 2; }
                console.log(t, u, twice((x) => x * 2, 3), early(2), nested(3), (1, 2), has, q);
                """;
        Path dir = Files.createTempDirectory("jsgen");
        Path module = dir.resolve("Sample.java");
        Files.writeString(module, Main.translate("sample.js", source, "Sample"));
        List<String> args = new ArrayList<>(List.of("-nowarn", "-d", dir.resolve("classes").toString(), module.toString()));
        try (Stream<Path> runtime = Files.list(Path.of(System.getProperty("duke.kernelSources"), "duke/js/rt"))) {
            runtime.map(Path::toString).forEach(args::add);
        }
        Files.createDirectories(dir.resolve("classes"));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)),
                () -> Main.translate("sample.js", source, "Sample"));
    }
}
