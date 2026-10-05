package duke.js;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Turns source text into tokens. Strings are ASCII only, because the runtime's strings are bytes. */
final class Lexer {

    private static final Set<String> KEYWORDS = Set.of(
            "var", "let", "const", "function", "return", "if", "else", "while", "do", "for", "break",
            "continue", "true", "false", "null", "undefined", "typeof", "this", "new", "throw", "try", "catch",
            "finally", "class", "delete", "in", "instanceof", "void", "switch", "case", "default");

    // Longest first, so "===" wins over "==" and "=".
    private static final String[] PUNCTUATORS = {
        ">>>=", "...", "===", "!==", "**=", "<<=", ">>=", ">>>", "&&=", "||=", "??=",
        "=>", "==", "!=", "<=", ">=", "&&", "||", "??", "++", "--", "+=", "-=", "*=", "/=", "%=", "&=", "|=",
        "^=", "<<", ">>", "**", "?.",
        "{", "}", "(", ")", "[", "]", ";", ",", "<", ">", "+", "-", "*", "/", "%", "&", "|", "^", "!", "~",
        "?", ":", "=", ".",
    };

    private final String file;
    private final String src;
    private int pos;
    private int line;

    Lexer(String file, String src, int firstLine) {
        this.file = file;
        this.src = src;
        this.line = firstLine;
    }

    List<Token> tokenize() {
        List<Token> out = new ArrayList<>();
        boolean newline = false;
        while (true) {
            int before = line;
            skipTrivia();
            newline = newline || line > before;
            if (pos >= src.length()) {
                out.add(new Token(Token.Kind.EOF, "", 0, line, true, null, null));
                return out;
            }
            out.add(next(newline));
            newline = false;
        }
    }

    private Token next(boolean newline) {
        char c = src.charAt(pos);
        if (Character.isDigit(c)) {
            return number(newline);
        }
        if (c == '"' || c == '\'') {
            return new Token(Token.Kind.STR, string(c), 0, line, newline, null, null);
        }
        if (c == '`') {
            return template(newline);
        }
        if (Character.isLetter(c) || c == '_' || c == '$') {
            int start = pos;
            while (pos < src.length() && (Character.isLetterOrDigit(src.charAt(pos)) || src.charAt(pos) == '_'
                    || src.charAt(pos) == '$')) {
                pos++;
            }
            String word = src.substring(start, pos);
            return new Token(KEYWORDS.contains(word) ? Token.Kind.KEYWORD : Token.Kind.IDENT, word, 0, line,
                    newline, null, null);
        }
        for (String p : PUNCTUATORS) {
            if (src.startsWith(p, pos)) {
                pos += p.length();
                return new Token(Token.Kind.PUNCT, p, 0, line, newline, null, null);
            }
        }
        throw error("unexpected character '" + c + "'");
    }

    private Token number(boolean newline) {
        int start = pos;
        long value;
        if (src.startsWith("0x", pos) || src.startsWith("0X", pos)) {
            pos += 2;
            int digits = pos;
            while (pos < src.length() && Character.digit(src.charAt(pos), 16) >= 0) {
                pos++;
            }
            value = Long.parseLong(src.substring(digits, pos), 16);
        } else {
            while (pos < src.length() && (Character.isDigit(src.charAt(pos)) || src.charAt(pos) == '_')) {
                pos++;
            }
            if (pos < src.length() && (src.charAt(pos) == '.' || src.charAt(pos) == 'e' || src.charAt(pos) == 'E')) {
                throw error("floating-point numbers are not supported yet, only integers");
            }
            value = Long.parseLong(src.substring(start, pos).replace("_", ""));
        }
        if (value >= 1L << 53) {
            throw error("integer literal is beyond 2^53 and would not be exact in JavaScript either");
        }
        return new Token(Token.Kind.NUM, src.substring(start, pos), value, line, newline, null, null);
    }

    private String string(char quote) {
        pos++;
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= src.length() || src.charAt(pos) == '\n') {
                throw error("unterminated string");
            }
            char c = src.charAt(pos++);
            if (c == quote) {
                return sb.toString();
            }
            sb.append(c == '\\' ? escape() : ascii(c));
        }
    }

    private Token template(boolean newline) {
        int startLine = line;
        pos++;
        List<String> chunks = new ArrayList<>();
        List<String> exprs = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= src.length()) {
                throw error("unterminated template literal");
            }
            char c = src.charAt(pos++);
            if (c == '`') {
                chunks.add(sb.toString());
                return new Token(Token.Kind.TEMPLATE, "`", 0, startLine, newline, chunks, exprs);
            } else if (c == '$' && pos < src.length() && src.charAt(pos) == '{') {
                pos++;
                chunks.add(sb.toString());
                sb.setLength(0);
                exprs.add(templateExpression());
            } else if (c == '\\') {
                sb.append(escape());
            } else {
                if (c == '\n') {
                    line++;
                }
                sb.append(ascii(c));
            }
        }
    }

    /** Returns the source between {@code ${} and its matching brace, skipping nested strings and braces. */
    private String templateExpression() {
        int start = pos;
        int depth = 1;
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == '"' || c == '\'' || c == '`') {
                if (c == '`') {
                    template(false);
                } else {
                    string(c);
                }
                continue;
            }
            pos++;
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return src.substring(start, pos - 1);
            } else if (c == '\n') {
                line++;
            }
        }
        throw error("unterminated template expression");
    }

    private char escape() {
        if (pos >= src.length()) {
            throw error("unterminated escape");
        }
        char c = src.charAt(pos++);
        return switch (c) {
            case 'n' -> '\n';
            case 't' -> '\t';
            case 'r' -> '\r';
            case '0' -> '\0';
            case 'b' -> '\b';
            case 'f' -> '\f';
            case 'v' -> '\u000B';
            case 'x' -> hex(2);
            case 'u' -> hex(4);
            default -> ascii(c);
        };
    }

    private char hex(int digits) {
        if (pos + digits > src.length()) {
            throw error("bad escape");
        }
        int value = Integer.parseInt(src.substring(pos, pos + digits), 16);
        pos += digits;
        return ascii((char) value);
    }

    private char ascii(char c) {
        if (c > 0x7F) {
            throw error("only ASCII strings are supported for now");
        }
        return c;
    }

    private void skipTrivia() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == '\n') {
                line++;
                pos++;
            } else if (Character.isWhitespace(c)) {
                pos++;
            } else if (src.startsWith("//", pos)) {
                while (pos < src.length() && src.charAt(pos) != '\n') {
                    pos++;
                }
            } else if (src.startsWith("/*", pos)) {
                int end = src.indexOf("*/", pos + 2);
                if (end < 0) {
                    throw error("unterminated comment");
                }
                for (int i = pos; i < end; i++) {
                    if (src.charAt(i) == '\n') {
                        line++;
                    }
                }
                pos = end + 2;
            } else {
                return;
            }
        }
    }

    private JsException error(String message) {
        return new JsException(file, line, message);
    }
}
