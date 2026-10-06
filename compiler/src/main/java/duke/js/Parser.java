package duke.js;

import duke.js.Node.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A recursive-descent parser for the supported subset of ECMAScript. */
final class Parser {

    private static final Map<String, Integer> BINARY = Map.ofEntries(
            Map.entry("??", 1), Map.entry("||", 2), Map.entry("&&", 3), Map.entry("|", 4), Map.entry("^", 5),
            Map.entry("&", 6), Map.entry("==", 7), Map.entry("!=", 7), Map.entry("===", 7), Map.entry("!==", 7),
            Map.entry("<", 8), Map.entry(">", 8), Map.entry("<=", 8), Map.entry(">=", 8),
            Map.entry("instanceof", 8), Map.entry("in", 8),
            Map.entry("<<", 9), Map.entry(">>", 9), Map.entry(">>>", 9),
            Map.entry("+", 10), Map.entry("-", 10), Map.entry("*", 11), Map.entry("/", 11), Map.entry("%", 11),
            Map.entry("**", 12));

    private static final Set<String> ASSIGN = Set.of("=", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "<<=",
            ">>=", ">>>=", "**=", "&&=", "||=", "??=");

    private static final Set<String> UNSUPPORTED = Set.of("class", "switch");

    private final String file;
    private final List<Token> tokens;
    private int pos;

    Parser(String file, String source) {
        this.file = file;
        this.tokens = new Lexer(file, source, 1).tokenize();
    }

    List<Stmt> parseProgram() {
        List<Stmt> body = new ArrayList<>();
        while (peek().kind() != Token.Kind.EOF) {
            body.add(statement());
        }
        return body;
    }

    // ---- statements ----

    private Stmt statement() {
        Token t = peek();
        int line = t.line();
        if (t.kind() == Token.Kind.KEYWORD && UNSUPPORTED.contains(t.text())) {
            throw error(t, "'" + t.text() + "' is not supported yet");
        }
        if (t.is("{")) {
            return block();
        }
        if (t.is(";")) {
            pos++;
            return new Empty(line);
        }
        if (t.is("var") || t.is("let") || t.is("const")) {
            Stmt decl = varDecl();
            semicolon();
            return decl;
        }
        if (t.is("function")) {
            pos++;
            return new FunctionDecl(function(expectIdent(), false, line), line);
        }
        if (t.is("if")) {
            pos++;
            expect("(");
            Expr test = expression();
            expect(")");
            Stmt then = statement();
            Stmt otherwise = null;
            if (peek().is("else")) {
                pos++;
                otherwise = statement();
            }
            return new If(test, then, otherwise, line);
        }
        if (t.is("while")) {
            pos++;
            expect("(");
            Expr test = expression();
            expect(")");
            return new While(test, statement(), line);
        }
        if (t.is("do")) {
            pos++;
            Stmt body = statement();
            expect("while");
            expect("(");
            Expr test = expression();
            expect(")");
            if (peek().is(";")) {
                pos++;
            }
            return new DoWhile(body, test, line);
        }
        if (t.is("for")) {
            return forStatement();
        }
        if (t.is("return")) {
            pos++;
            Expr value = null;
            if (!peek().is(";") && !peek().is("}") && !peek().newlineBefore() && peek().kind() != Token.Kind.EOF) {
                value = expression();
            }
            semicolon();
            return new Return(value, line);
        }
        if (t.is("throw")) {
            pos++;
            if (peek().newlineBefore()) {
                throw error(peek(), "a line break is not allowed after 'throw'");
            }
            Expr value = expression();
            semicolon();
            return new Throw(value, line);
        }
        if (t.is("try")) {
            pos++;
            Block block = block();
            String param = null;
            Block handler = null;
            Block finalizer = null;
            if (accept("catch")) {
                if (accept("(")) {
                    param = expectIdent();
                    expect(")");
                }
                handler = block();
            }
            if (accept("finally")) {
                finalizer = block();
            }
            if (handler == null && finalizer == null) {
                throw error(peek(), "missing catch or finally after try");
            }
            return new Try(block, param, handler, finalizer, line);
        }
        if (t.is("break")) {
            pos++;
            semicolon();
            return new Break(line);
        }
        if (t.is("continue")) {
            pos++;
            semicolon();
            return new Continue(line);
        }
        Expr e = expression();
        semicolon();
        return new ExprStmt(e, line);
    }

    private Block block() {
        int line = expect("{").line();
        List<Stmt> body = new ArrayList<>();
        while (!peek().is("}")) {
            if (peek().kind() == Token.Kind.EOF) {
                throw error(peek(), "expected '}'");
            }
            body.add(statement());
        }
        pos++;
        return new Block(body, line);
    }

    private VarDecl varDecl() {
        Token kind = tokens.get(pos++);
        List<Declarator> declarators = new ArrayList<>();
        do {
            String name = expectIdent();
            Expr init = null;
            if (peek().is("=")) {
                pos++;
                init = assignment();
            } else if (kind.is("const")) {
                throw error(peek(), "missing initializer in const declaration");
            }
            declarators.add(new Declarator(name, init));
        } while (accept(","));
        return new VarDecl(kind.text(), declarators, kind.line());
    }

    private Stmt forStatement() {
        int line = tokens.get(pos++).line();
        expect("(");
        Stmt init = null;
        if (peek().is("let") || peek().is("const") || peek().is("var")) {
            String kind = peek().text();
            boolean forOf = tokens.get(pos + 2).kind() == Token.Kind.IDENT && tokens.get(pos + 2).text().equals("of");
            boolean forIn = tokens.get(pos + 2).is("in");
            if (tokens.get(pos + 1).kind() == Token.Kind.IDENT && (forOf || forIn)) {
                pos++;
                String name = expectIdent();
                pos++;
                Expr iterable = forIn ? expression() : assignment();
                expect(")");
                return new ForOf(kind, name, iterable, statement(), line, forIn);
            }
            init = varDecl();
        } else if (!peek().is(";")) {
            Expr e = expression();
            init = new ExprStmt(e, e.line());
        }
        expect(";");
        Expr test = peek().is(";") ? null : expression();
        expect(";");
        Expr update = peek().is(")") ? null : expression();
        expect(")");
        return new For(init, test, update, statement(), line);
    }

    private void semicolon() {
        Token t = peek();
        if (t.is(";")) {
            pos++;
        } else if (!t.is("}") && t.kind() != Token.Kind.EOF && !t.newlineBefore()) {
            throw error(t, "expected ';' but found " + t);
        }
    }

    // ---- expressions ----

    Expr expression() {
        Expr first = assignment();
        if (!peek().is(",")) {
            return first;
        }
        List<Expr> all = new ArrayList<>(List.of(first));
        while (accept(",")) {
            all.add(assignment());
        }
        return new Sequence(all, first.line());
    }

    private Expr assignment() {
        if (isArrowAhead()) {
            return arrow();
        }
        Expr left = conditional();
        Token t = peek();
        if (t.kind() == Token.Kind.PUNCT && ASSIGN.contains(t.text())) {
            if (!(left instanceof Ident || left instanceof Member || left instanceof Index)) {
                throw error(t, "invalid assignment target");
            }
            pos++;
            return new Assign(t.text(), left, assignment(), t.line());
        }
        return left;
    }

    private boolean isArrowAhead() {
        Token t = peek();
        if (t.kind() == Token.Kind.IDENT) {
            return tokens.get(pos + 1).is("=>");
        }
        if (!t.is("(")) {
            return false;
        }
        int depth = 0;
        for (int i = pos; i < tokens.size(); i++) {
            Token u = tokens.get(i);
            if (u.is("(")) {
                depth++;
            } else if (u.is(")") && --depth == 0) {
                return tokens.get(i + 1).is("=>");
            } else if (u.kind() == Token.Kind.EOF) {
                return false;
            }
        }
        return false;
    }

    private Expr arrow() {
        int line = peek().line();
        List<String> params = new ArrayList<>();
        if (peek().is("(")) {
            pos++;
            while (!peek().is(")")) {
                params.add(expectIdent());
                if (!peek().is(")")) {
                    expect(",");
                }
            }
            pos++;
        } else {
            params.add(expectIdent());
        }
        expect("=>");
        List<Stmt> body;
        if (peek().is("{")) {
            body = block().body();
        } else {
            Expr e = assignment();
            body = List.of(new Return(e, e.line()));
        }
        return new FuncExpr(new Function(null, params, body, true, line), line);
    }

    private Expr conditional() {
        Expr test = binary(1);
        if (!peek().is("?")) {
            return test;
        }
        int line = tokens.get(pos++).line();
        Expr then = assignment();
        expect(":");
        return new Conditional(test, then, assignment(), line);
    }

    private Expr binary(int minPrecedence) {
        Expr left = unary();
        while (true) {
            Token t = peek();
            boolean operator = t.kind() == Token.Kind.PUNCT || t.is("instanceof") || t.is("in");
            Integer prec = operator ? BINARY.get(t.text()) : null;
            if (prec == null || prec < minPrecedence) {
                return left;
            }
            pos++;
            // "**" is right-associative; everything else groups to the left.
            Expr right = binary(t.text().equals("**") ? prec : prec + 1);
            left = switch (t.text()) {
                case "&&", "||", "??" -> new Logical(t.text(), left, right, t.line());
                default -> new Binary(t.text(), left, right, t.line());
            };
        }
    }

    private Expr unary() {
        Token t = peek();
        if (t.is("!") || t.is("-") || t.is("+") || t.is("~") || t.is("typeof") || t.is("delete") || t.is("void")) {
            pos++;
            return new Unary(t.text(), unary(), t.line());
        }
        if (t.is("++") || t.is("--")) {
            pos++;
            Expr target = unary();
            checkUpdateTarget(target, t);
            return new Update(t.text(), true, target, t.line());
        }
        Expr e = callOrMember();
        Token post = peek();
        if ((post.is("++") || post.is("--")) && !post.newlineBefore()) {
            pos++;
            checkUpdateTarget(e, post);
            return new Update(post.text(), false, e, post.line());
        }
        return e;
    }

    private void checkUpdateTarget(Expr target, Token at) {
        if (!(target instanceof Ident || target instanceof Member || target instanceof Index)) {
            throw error(at, "invalid increment/decrement target");
        }
    }

    /** {@code new C(args)}: C is a member expression, so {@code new a.b.C(x).d()} constructs before it calls. */
    private Expr newExpression() {
        int line = tokens.get(pos++).line();
        Expr callee = peek().is("new") ? newExpression() : primary();
        while (true) {
            Token t = peek();
            if (t.is(".")) {
                pos++;
                Token name = tokens.get(pos++);
                if (name.kind() != Token.Kind.IDENT && name.kind() != Token.Kind.KEYWORD) {
                    throw error(name, "expected a property name but found " + name);
                }
                callee = new Member(callee, name.text(), t.line());
            } else if (t.is("[")) {
                pos++;
                Expr index = expression();
                expect("]");
                callee = new Index(callee, index, t.line());
            } else {
                break;
            }
        }
        List<Expr> args = new ArrayList<>();
        if (peek().is("(")) {
            pos++;
            while (!peek().is(")")) {
                args.add(assignment());
                if (!peek().is(")")) {
                    expect(",");
                }
            }
            pos++;
        }
        return new New(callee, args, line);
    }

    private Expr callOrMember() {
        Expr e = peek().is("new") ? newExpression() : primary();
        while (true) {
            Token t = peek();
            if (t.is(".")) {
                pos++;
                Token name = tokens.get(pos++);
                if (name.kind() != Token.Kind.IDENT && name.kind() != Token.Kind.KEYWORD) {
                    throw error(name, "expected a property name but found " + name);
                }
                e = new Member(e, name.text(), t.line());
            } else if (t.is("[")) {
                pos++;
                Expr index = expression();
                expect("]");
                e = new Index(e, index, t.line());
            } else if (t.is("(")) {
                pos++;
                List<Expr> args = new ArrayList<>();
                while (!peek().is(")")) {
                    args.add(assignment());
                    if (!peek().is(")")) {
                        expect(",");
                    }
                }
                pos++;
                e = new Call(e, args, t.line());
            } else if (t.is("?.")) {
                throw error(t, "optional chaining is not supported yet");
            } else {
                return e;
            }
        }
    }

    private Expr primary() {
        Token t = tokens.get(pos++);
        int line = t.line();
        switch (t.kind()) {
            case NUM:
                return new Num(t.number(), line);
            case STR:
                return new Str(t.text(), line);
            case TEMPLATE:
                return template(t);
            case IDENT:
                return new Ident(t.text(), line);
            case KEYWORD:
                switch (t.text()) {
                    case "true": return new Lit(Literal.TRUE, line);
                    case "false": return new Lit(Literal.FALSE, line);
                    case "null": return new Lit(Literal.NULL, line);
                    case "undefined": return new Lit(Literal.UNDEFINED, line);
                    case "this": return new This(line);
                    case "function": {
                        String name = peek().kind() == Token.Kind.IDENT ? expectIdent() : null;
                        return new FuncExpr(function(name, false, line), line);
                    }
                    default:
                        if (UNSUPPORTED.contains(t.text())) {
                            throw error(t, "'" + t.text() + "' is not supported yet");
                        }
                        throw error(t, "unexpected " + t);
                }
            case PUNCT:
                if (t.is("(")) {
                    Expr e = expression();
                    expect(")");
                    return e;
                }
                if (t.is("[")) {
                    List<Expr> elements = new ArrayList<>();
                    while (!peek().is("]")) {
                        if (peek().is("...")) {
                            throw error(peek(), "spread is not supported yet");
                        }
                        elements.add(assignment());
                        if (!peek().is("]")) {
                            expect(",");
                        }
                    }
                    pos++;
                    return new ArrayLit(elements, line);
                }
                if (t.is("{")) {
                    return objectLiteral(line);
                }
                throw error(t, "unexpected " + t);
            default:
                throw error(t, "unexpected " + t);
        }
    }

    private Expr objectLiteral(int line) {
        List<Property> props = new ArrayList<>();
        while (!peek().is("}")) {
            Token key = tokens.get(pos++);
            String name;
            switch (key.kind()) {
                case IDENT, KEYWORD, STR -> name = key.text();
                case NUM -> name = Long.toString(key.number());
                default -> throw error(key, "unexpected " + key + " in object literal");
            }
            Expr value;
            if (accept(":")) {
                value = assignment();
            } else if (peek().is("(")) {
                value = new FuncExpr(function(name, false, key.line()), key.line());
            } else if (key.kind() == Token.Kind.IDENT) {
                value = new Ident(name, key.line());
            } else {
                throw error(peek(), "expected ':' but found " + peek());
            }
            props.add(new Property(name, value));
            if (!peek().is("}")) {
                expect(",");
            }
        }
        pos++;
        return new ObjectLit(props, line);
    }

    private Expr template(Token t) {
        List<Expr> exprs = new ArrayList<>();
        for (String source : t.exprs()) {
            Parser inner = new Parser(file, source);
            exprs.add(inner.expression());
            if (inner.peek().kind() != Token.Kind.EOF) {
                throw error(t, "unexpected " + inner.peek() + " in template expression");
            }
        }
        return new Template(t.chunks(), exprs, t.line());
    }

    /** Parses {@code (params) { body }}. */
    private Function function(String name, boolean arrow, int line) {
        expect("(");
        List<String> params = new ArrayList<>();
        while (!peek().is(")")) {
            params.add(expectIdent());
            if (!peek().is(")")) {
                expect(",");
            }
        }
        pos++;
        return new Function(name, params, block().body(), arrow, line);
    }

    // ---- token helpers ----

    private Token peek() {
        return tokens.get(pos);
    }

    private boolean accept(String punct) {
        if (peek().is(punct)) {
            pos++;
            return true;
        }
        return false;
    }

    private Token expect(String punct) {
        Token t = tokens.get(pos);
        if (!t.is(punct)) {
            throw error(t, "expected '" + punct + "' but found " + t);
        }
        pos++;
        return t;
    }

    private String expectIdent() {
        Token t = tokens.get(pos);
        if (t.kind() != Token.Kind.IDENT) {
            throw error(t, "expected a name but found " + t);
        }
        pos++;
        return t.text();
    }

    private JsException error(Token at, String message) {
        return new JsException(file, at.line(), message);
    }
}
