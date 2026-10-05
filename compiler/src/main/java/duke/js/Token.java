package duke.js;

import java.util.List;

/**
 * One lexical token. {@code text} is the identifier, punctuator or decoded string; templates carry
 * their literal chunks in {@code chunks} and the source of each {@code ${}} in {@code exprs}.
 */
record Token(Kind kind, String text, long number, int line, boolean newlineBefore, List<String> chunks,
        List<String> exprs) {

    enum Kind { NUM, STR, TEMPLATE, IDENT, KEYWORD, PUNCT, EOF }

    boolean is(String punctOrKeyword) {
        return (kind == Kind.PUNCT || kind == Kind.KEYWORD) && text.equals(punctOrKeyword);
    }

    @Override
    public String toString() {
        return kind == Kind.EOF ? "end of file" : kind == Kind.NUM ? Long.toString(number) : "'" + text + "'";
    }
}
