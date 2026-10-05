package duke.js;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.stream.Stream;
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
                Arguments.of("new Foo();", "'new' is not supported yet"),
                Arguments.of("try { } catch (e) { }", "'try' is not supported yet"),
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
    void compilesClosuresAndTemplates() throws IOException {
        Main.compile("t.js", """
                function make() { let n = 0; return () => `${++n}`; }
                const f = make();
                for (let i = 0; i < 3; i++) console.log(f(), [i, { i }]);
                """);
    }
}
