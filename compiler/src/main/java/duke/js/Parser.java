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

    private static final Set<String> UNSUPPORTED = Set.of("class");

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
        return withHoists(body);
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
                Pat catchPattern = null;
                if (accept("(")) {
                    if (atPattern()) {
                        catchPattern = bindingTarget();
                        param = hidden("c");
                    } else {
                        param = expectIdent();
                    }
                    expect(")");
                }
                handler = block();
                if (catchPattern != null) {
                    List<Binding> bindings = new ArrayList<>();
                    flatten(catchPattern, new Ident(param, line), line, bindings);
                    List<Stmt> inner = new ArrayList<>();
                    inner.add(new VarDecl("let", declarators(bindings), line));
                    inner.addAll(handler.body());
                    handler = new Block(inner, handler.line());
                }
            }
            if (accept("finally")) {
                finalizer = block();
            }
            if (handler == null && finalizer == null) {
                throw error(peek(), "missing catch or finally after try");
            }
            return new Try(block, param, handler, finalizer, line);
        }
        if (t.is("break") || t.is("continue")) {
            pos++;
            String label = null;
            if (peek().kind() == Token.Kind.IDENT && !peek().newlineBefore()) {
                label = tokens.get(pos++).text();
            }
            semicolon();
            return t.is("break") ? new Break(label, line) : new Continue(label, line);
        }
        if (t.is("switch")) {
            return switchStatement();
        }
        if (t.kind() == Token.Kind.IDENT && tokens.get(pos + 1).is(":")) {
            pos += 2;
            return new Labeled(t.text(), statement(), line);
        }
        Expr e = expression();
        semicolon();
        return new ExprStmt(e, line);
    }

    private Stmt switchStatement() {
        int line = tokens.get(pos++).line();
        expect("(");
        Expr discriminant = expression();
        expect(")");
        expect("{");
        List<Case> cases = new ArrayList<>();
        boolean sawDefault = false;
        while (!peek().is("}")) {
            Expr test = null;
            if (accept("default")) {
                if (sawDefault) {
                    throw error(peek(), "more than one default clause in switch");
                }
                sawDefault = true;
            } else {
                expect("case");
                test = expression();
            }
            expect(":");
            List<Stmt> body = new ArrayList<>();
            while (!peek().is("case") && !peek().is("default") && !peek().is("}")) {
                if (peek().kind() == Token.Kind.EOF) {
                    throw error(peek(), "expected '}'");
                }
                body.add(statement());
            }
            cases.add(new Case(test, body));
        }
        pos++;
        return new Switch(discriminant, cases, line);
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
            if (atPattern()) {
                int line = peek().line();
                Pat pattern = bindingTarget();
                expect("=");
                List<Binding> bindings = new ArrayList<>();
                flatten(pattern, assignment(), line, bindings);
                declarators.addAll(declarators(bindings));
                continue;
            }
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
            if (tokens.get(pos + 1).is("[") || tokens.get(pos + 1).is("{")) {
                Token after = tokens.get(matching(pos + 1) + 1);
                boolean patternOf = after.kind() == Token.Kind.IDENT && after.text().equals("of");
                if (patternOf || after.is("in")) {
                    pos++;
                    Pat pattern = bindingTarget();
                    pos++;
                    Expr iterable = after.is("in") ? expression() : assignment();
                    expect(")");
                    String hidden = hidden("f");
                    List<Binding> bindings = new ArrayList<>();
                    flatten(pattern, new Ident(hidden, line), line, bindings);
                    Stmt body = new Block(new ArrayList<>(List.of(new VarDecl(kind, declarators(bindings), line),
                            statement())), line);
                    return new ForOf(kind, hidden, iterable, body, line, after.is("in"));
                }
            }
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
        if (t.is("=") && (left instanceof ArrayLit || left instanceof ObjectLit)) {
            pos++;
            return destructuringAssignment(left, t.line());
        }
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

    private int hiddenParameters;

    /**
     * Parses a parameter list after its opening parenthesis. A parameter with a default or a rest parameter is
     * desugared: the function gets a positional hidden name, and statements added to {@code prologue} (which run
     * first in the body) bind the real name.
     */
    private void parameters(List<String> names, List<Stmt> prologue) {
        while (!peek().is(")")) {
            if (peek().is("...")) {
                int line = tokens.get(pos++).line();
                String name = expectIdent();
                Expr rest = new Internal("restArgs", List.of(new Num(names.size(), line)), line);
                prologue.add(new VarDecl("var", List.of(new Declarator(name, rest)), line));
                if (!peek().is(")")) {
                    throw error(peek(), "a rest parameter must be last");
                }
                break;
            }
            Token t = peek();
            if (atPattern()) {
                Pat pattern = bindingTarget();
                String hidden = "$p" + hiddenParameters++;
                Expr source = new Ident(hidden, t.line());
                if (accept("=")) {
                    source = withDefault(source, assignment(), t.line());
                }
                List<Binding> bindings = new ArrayList<>();
                flatten(pattern, source, t.line(), bindings);
                prologue.add(new VarDecl("var", declarators(bindings), t.line()));
                names.add(hidden);
                if (!peek().is(")")) {
                    expect(",");
                }
                continue;
            }
            String name = expectIdent();
            if (accept("=")) {
                String hidden = "$p" + hiddenParameters++;
                Expr fallback = assignment();
                Expr value = new Conditional(new Binary("===", new Ident(hidden, t.line()), new Lit(Literal.UNDEFINED, t.line()),
                        t.line()), fallback, new Ident(hidden, t.line()), t.line());
                prologue.add(new VarDecl("var", List.of(new Declarator(name, value)), t.line()));
                names.add(hidden);
            } else {
                names.add(name);
            }
            if (!peek().is(")")) {
                expect(",");
            }
        }
        pos++;
    }

    private Expr arrow() {
        int line = peek().line();
        List<String> params = new ArrayList<>();
        List<Stmt> body = new ArrayList<>();
        if (peek().is("(")) {
            pos++;
            parameters(params, body);
        } else {
            params.add(expectIdent());
        }
        expect("=>");
        List<Stmt> outerHoists = hoists;
        hoists = new ArrayList<>();
        if (peek().is("{")) {
            body.addAll(block().body());
        } else {
            Expr e = assignment();
            body.add(new Return(e, e.line()));
        }
        List<Stmt> all = withHoists(body);
        hoists = outerHoists;
        return new FuncExpr(new Function(null, params, all, true, line), line);
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
                args.add(argument());
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
        List<ChainOp> ops = null;
        while (true) {
            Token t = peek();
            boolean optional = t.is("?.");
            if (optional) {
                pos++;
                t = peek();
                if (ops == null) {
                    ops = new ArrayList<>();
                }
                if (t.is("(")) {
                    pos++;
                    ops.add(new ChainOp('c', null, null, arguments(), true));
                } else if (t.is("[")) {
                    pos++;
                    Expr index = expression();
                    expect("]");
                    ops.add(new ChainOp('i', null, index, null, true));
                } else {
                    ops.add(new ChainOp('m', propertyName(), null, null, true));
                }
                continue;
            }
            if (t.is(".")) {
                pos++;
                String name = propertyName();
                if (ops != null) {
                    ops.add(new ChainOp('m', name, null, null, false));
                } else {
                    e = new Member(e, name, t.line());
                }
            } else if (t.is("[")) {
                pos++;
                Expr index = expression();
                expect("]");
                if (ops != null) {
                    ops.add(new ChainOp('i', null, index, null, false));
                } else {
                    e = new Index(e, index, t.line());
                }
            } else if (t.is("(")) {
                pos++;
                List<Expr> args = arguments();
                if (ops != null) {
                    ops.add(new ChainOp('c', null, null, args, false));
                } else {
                    e = new Call(e, args, t.line());
                }
            } else if (t.kind() == Token.Kind.TEMPLATE) {
                if (ops != null) {
                    throw error(t, "a tagged template cannot follow an optional chain");
                }
                pos++;
                e = new TaggedTemplate(e, t.chunks(), t.raws(), templateExpressions(t), t.line());
            } else {
                return ops == null ? e : new Chain(e, ops, e.line());
            }
        }
    }

    /** One call argument or array element: an expression, or {@code ...expression}. */
    private Expr argument() {
        if (peek().is("...")) {
            int line = tokens.get(pos++).line();
            return new Spread(assignment(), line);
        }
        return assignment();
    }

    /** A property name after a dot: an identifier or a keyword. */
    private String propertyName() {
        Token name = tokens.get(pos++);
        if (name.kind() != Token.Kind.IDENT && name.kind() != Token.Kind.KEYWORD) {
            throw error(name, "expected a property name but found " + name);
        }
        return name.text();
    }

    /** Call arguments, after the opening parenthesis. */
    private List<Expr> arguments() {
        List<Expr> args = new ArrayList<>();
        while (!peek().is(")")) {
            args.add(argument());
            if (!peek().is(")")) {
                expect(",");
            }
        }
        pos++;
        return args;
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
                        if (peek().is(",")) {
                            pos++;
                            elements.add(new Hole(line));
                            continue;
                        }
                        elements.add(argument());
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
            if (peek().is("...")) {
                int spreadLine = tokens.get(pos++).line();
                props.add(new Property(null, null, new Spread(assignment(), spreadLine), 'i'));
                if (!peek().is("}")) {
                    expect(",");
                }
                continue;
            }
            Token key = tokens.get(pos++);
            char kind = 'i';
            if (key.kind() == Token.Kind.IDENT && (key.text().equals("get") || key.text().equals("set"))
                    && startsPropertyName(peek())) {
                kind = key.text().charAt(0);
                key = tokens.get(pos++);
            }
            String name = null;
            Expr computed = null;
            if (key.is("[")) {
                computed = assignment();
                expect("]");
            } else {
                switch (key.kind()) {
                    case IDENT, KEYWORD, STR -> name = key.text();
                    case NUM -> name = Long.toString(key.number());
                    default -> throw error(key, "unexpected " + key + " in object literal");
                }
            }
            Expr value;
            if (kind != 'i') {
                value = new FuncExpr(function(name == null ? null : (kind == 'g' ? "get " : "set ") + name, false, key.line()),
                        key.line());
            } else if (accept(":")) {
                value = assignment();
            } else if (peek().is("(")) {
                value = new FuncExpr(function(name, false, key.line()), key.line());
            } else if (computed == null && key.kind() == Token.Kind.IDENT) {
                value = new Ident(name, key.line());
                if (peek().is("=")) {
                    // {a = 1} is only meaningful as a destructuring pattern; toPattern reads it back.
                    pos++;
                    value = new Assign("=", value, assignment(), key.line());
                }
            } else {
                throw error(peek(), "expected ':' but found " + peek());
            }
            props.add(new Property(name, computed, value, kind));
            if (!peek().is("}")) {
                expect(",");
            }
        }
        pos++;
        return new ObjectLit(props, line);
    }

    /** Can this token begin a property name? It does after {@code get} or {@code set}, which then mark an accessor. */
    private static boolean startsPropertyName(Token t) {
        return t.kind() == Token.Kind.IDENT || t.kind() == Token.Kind.KEYWORD || t.kind() == Token.Kind.STR
                || t.kind() == Token.Kind.NUM || t.is("[");
    }

    private Expr template(Token t) {
        return new Template(t.chunks(), templateExpressions(t), t.line());
    }

    private List<Expr> templateExpressions(Token t) {
        List<Expr> exprs = new ArrayList<>();
        for (String source : t.exprs()) {
            Parser inner = new Parser(file, source);
            exprs.add(inner.expression());
            if (inner.peek().kind() != Token.Kind.EOF) {
                throw error(t, "unexpected " + inner.peek() + " in template expression");
            }
        }
        return exprs;
    }

    /** Parses {@code (params) { body }}. */
    private Function function(String name, boolean arrow, int line) {
        expect("(");
        List<String> params = new ArrayList<>();
        List<Stmt> body = new ArrayList<>();
        List<Stmt> outerHoists = hoists;
        hoists = new ArrayList<>();
        parameters(params, body);
        body.addAll(block().body());
        List<Stmt> all = withHoists(body);
        hoists = outerHoists;
        return new Function(name, params, all, arrow, line);
    }

    // ---- destructuring ----
    //
    // A pattern is parsed (or recovered from an array or object literal that turns out to be an assignment
    // target) and then flattened into plain bindings over hidden temporaries, so the rest of the compiler
    // never sees one.

    private sealed interface Pat permits PName, PTarget, PArray, PObject {}

    private record PName(String name) implements Pat {}

    /** A member or index expression on the left of a destructuring assignment. */
    private record PTarget(Expr target) implements Pat {}

    /** {@code target} is null for a hole. */
    private record PElem(Pat target, Expr dflt) {}

    private record PArray(List<PElem> elems, Pat rest) implements Pat {}

    private record PProp(String key, Expr computed, Pat target, Expr dflt) {}

    private record PObject(List<PProp> props, Pat rest) implements Pat {}

    private record Binding(Expr target, Expr value) {}

    private int hiddenNames;
    /** Declarations that belong at the top of the function being parsed: temporaries for destructuring assignments. */
    private List<Stmt> hoists = new ArrayList<>();

    private String hidden(String prefix) {
        return "$" + prefix + hiddenNames++;
    }

    private List<Stmt> withHoists(List<Stmt> body) {
        List<Stmt> all = new ArrayList<>(hoists);
        all.addAll(body);
        return all;
    }

    private boolean atPattern() {
        return peek().is("[") || peek().is("{");
    }

    /** A binding target: a name or a nested pattern. */
    private Pat bindingTarget() {
        if (peek().is("[")) {
            pos++;
            List<PElem> elems = new ArrayList<>();
            Pat rest = null;
            while (!peek().is("]")) {
                if (peek().is(",")) {
                    pos++;
                    elems.add(new PElem(null, null));
                    continue;
                }
                if (accept("...")) {
                    rest = bindingTarget();
                    if (!peek().is("]")) {
                        throw error(peek(), "a rest element must be last");
                    }
                    break;
                }
                Pat target = bindingTarget();
                Expr dflt = accept("=") ? assignment() : null;
                elems.add(new PElem(target, dflt));
                if (!peek().is("]")) {
                    expect(",");
                }
            }
            expect("]");
            return new PArray(elems, rest);
        }
        if (peek().is("{")) {
            pos++;
            List<PProp> props = new ArrayList<>();
            Pat rest = null;
            while (!peek().is("}")) {
                if (accept("...")) {
                    rest = bindingTarget();
                    if (!peek().is("}")) {
                        throw error(peek(), "a rest element must be last");
                    }
                    break;
                }
                Token key = tokens.get(pos++);
                String name = null;
                Expr computed = null;
                if (key.is("[")) {
                    computed = assignment();
                    expect("]");
                } else if (key.kind() == Token.Kind.IDENT || key.kind() == Token.Kind.KEYWORD || key.kind() == Token.Kind.STR) {
                    name = key.text();
                } else if (key.kind() == Token.Kind.NUM) {
                    name = Long.toString(key.number());
                } else {
                    throw error(key, "unexpected " + key + " in a pattern");
                }
                Pat target;
                if (accept(":")) {
                    target = bindingTarget();
                } else if (computed == null && key.kind() == Token.Kind.IDENT) {
                    target = new PName(name);
                } else {
                    throw error(peek(), "expected ':' but found " + peek());
                }
                Expr dflt = accept("=") ? assignment() : null;
                props.add(new PProp(name, computed, target, dflt));
                if (!peek().is("}")) {
                    expect(",");
                }
            }
            expect("}");
            return new PObject(props, rest);
        }
        return new PName(expectIdent());
    }

    /** Recovers a pattern from an expression that was parsed as an array or object literal before the {@code =}. */
    private Pat toPattern(Expr e) {
        switch (e) {
            case Ident id -> {
                return new PName(id.name());
            }
            case Member m -> {
                return new PTarget(m);
            }
            case Index i -> {
                return new PTarget(i);
            }
            case ArrayLit a -> {
                List<PElem> elems = new ArrayList<>();
                Pat rest = null;
                for (Expr element : a.elements()) {
                    if (element instanceof Hole) {
                        elems.add(new PElem(null, null));
                    } else if (element instanceof Spread sp) {
                        rest = toPattern(sp.value());
                    } else if (element instanceof Assign as && as.op().equals("=")) {
                        elems.add(new PElem(toPattern(as.target()), as.value()));
                    } else {
                        elems.add(new PElem(toPattern(element), null));
                    }
                }
                return new PArray(elems, rest);
            }
            case ObjectLit o -> {
                List<PProp> props = new ArrayList<>();
                Pat rest = null;
                for (Property p : o.properties()) {
                    if (p.key() == null && p.computed() == null) {
                        rest = toPattern(((Spread) p.value()).value());
                    } else if (p.value() instanceof Assign as && as.op().equals("=")) {
                        props.add(new PProp(p.key(), p.computed(), toPattern(as.target()), as.value()));
                    } else {
                        props.add(new PProp(p.key(), p.computed(), toPattern(p.value()), null));
                    }
                }
                return new PObject(props, rest);
            }
            default -> throw new JsException(file, e.line(), "invalid destructuring target");
        }
    }

    private static Expr withDefault(Expr value, Expr dflt, int line) {
        if (dflt == null) {
            return value;
        }
        return new Conditional(new Binary("===", value, new Lit(Literal.UNDEFINED, line), line), dflt, value, line);
    }

    /** Flattens a pattern matched against {@code source} into bindings, in evaluation order. */
    private void flatten(Pat pattern, Expr source, int line, List<Binding> out) {
        switch (pattern) {
            case PName n -> out.add(new Binding(new Ident(n.name(), line), source));
            case PTarget t -> out.add(new Binding(t.target(), source));
            case PArray a -> {
                String temp = hidden("d");
                out.add(new Binding(new Ident(temp, line), new Internal("toArray", List.of(source), line)));
                for (int i = 0; i < a.elems().size(); i++) {
                    PElem e = a.elems().get(i);
                    if (e.target() != null) {
                        Expr item = new Index(new Ident(temp, line), new Num(i, line), line);
                        flatten(e.target(), withDefault(item, e.dflt(), line), line, out);
                    }
                }
                if (a.rest() != null) {
                    Expr slice = new Call(new Member(new Ident(temp, line), "slice", line),
                            List.of(new Num(a.elems().size(), line)), line);
                    flatten(a.rest(), slice, line, out);
                }
            }
            case PObject o -> {
                String temp = hidden("d");
                out.add(new Binding(new Ident(temp, line), new Internal("requireObject", List.of(source), line)));
                List<Expr> used = new ArrayList<>();
                used.add(new Ident(temp, line));
                for (PProp p : o.props()) {
                    Expr key = p.computed() != null ? p.computed() : new Str(p.key(), line);
                    Expr item = p.computed() != null ? new Index(new Ident(temp, line), key, line)
                            : new Member(new Ident(temp, line), p.key(), line);
                    used.add(key);
                    flatten(p.target(), withDefault(item, p.dflt(), line), line, out);
                }
                if (o.rest() != null) {
                    flatten(o.rest(), new Internal("objectRest", used, line), line, out);
                }
            }
        }
    }

    /** Declarators for the bindings of a declaration's pattern. */
    private List<Declarator> declarators(List<Binding> bindings) {
        List<Declarator> out = new ArrayList<>();
        for (Binding b : bindings) {
            out.add(new Declarator(((Ident) b.target()).name(), b.value()));
        }
        return out;
    }

    /** Index of the bracket closing the one at {@code open}. */
    private int matching(int open) {
        int depth = 0;
        for (int i = open; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (t.is("[") || t.is("{") || t.is("(")) {
                depth++;
            } else if (t.is("]") || t.is("}") || t.is(")")) {
                if (--depth == 0) {
                    return i;
                }
            }
        }
        throw error(tokens.get(open), "unbalanced bracket");
    }

    /** {@code [a, b] = value} or {@code ({a, b} = value)}: assigns through hidden temporaries and yields the value. */
    private Expr destructuringAssignment(Expr left, int line) {
        Pat pattern = toPattern(left);
        Expr value = assignment();
        String temp = hidden("d");
        List<Binding> bindings = new ArrayList<>();
        bindings.add(new Binding(new Ident(temp, line), value));
        flatten(pattern, new Ident(temp, line), line, bindings);
        List<Expr> steps = new ArrayList<>();
        for (Binding b : bindings) {
            if (b.target() instanceof Ident id && id.name().startsWith("$d")) {
                hoists.add(new VarDecl("var", List.of(new Declarator(id.name(), null)), line));
            }
            steps.add(new Assign("=", b.target(), b.value(), line));
        }
        steps.add(new Ident(temp, line));
        return new Sequence(steps, line);
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
