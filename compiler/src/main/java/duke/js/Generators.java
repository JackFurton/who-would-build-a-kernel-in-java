package duke.js;

import duke.js.Node.*;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns the body of a generator into a state machine, so a generator needs no threads or coroutines.
 *
 * <p>The generator function becomes an ordinary function that declares the generator's variables, runs the
 * parameter setup, and returns a generator object around a resume function. That function is an arrow, so it
 * shares {@code this} and {@code arguments}; called with a mode (0 next, 1 throw, 2 return) and a value, it runs
 * a {@code switch} on a state number inside a loop. The body is cut into blocks at every {@code yield}; a block
 * ends by setting the state and continuing the loop, or by returning the yielded value.
 *
 * <p>Statements without a yield stay as they are. Statements with one are taken apart: conditionals and loops
 * become jumps between blocks, an expression with a yield in it is evaluated into temporaries in order, and
 * {@code try}/{@code catch}/{@code finally} becomes regions that the resume function consults when something
 * throws. Every variable the body declares is hoisted out, because the resume function runs afresh each time.
 */
final class Generators {

    private static final String STATE = "$s";
    private static final String MODE = "$mode";
    private static final String ARG = "$arg";
    private static final String LOOP = "$L";
    private static final String CAUGHT = "$e";
    private static final String RETURN_VALUE = "$rv";

    private static int unique;

    private final String file;
    private int line;

    private final List<List<Stmt>> blocks = new ArrayList<>();
    private final List<Region> blockRegion = new ArrayList<>();
    private final List<Boolean> terminated = new ArrayList<>();
    private int current;
    private Region region;
    private final List<Region> regions = new ArrayList<>();

    private final Set<String> hoisted = new LinkedHashSet<>();
    private final List<Stmt> functionDeclarations = new ArrayList<>();
    private final Deque<Set<String>> scopes = new ArrayDeque<>();
    private final Deque<Target> targets = new ArrayDeque<>();
    private final List<FinallyContext> finallys = new ArrayList<>();
    private final List<String> pendingLabels = new ArrayList<>();
    private int returnBlock = -1;

    /** A protected range of blocks: an exception thrown in one goes to {@code handler}. */
    private record Region(Region parent, boolean catches, int handler, String caughtVar, String completionVar,
            String exceptionVar) {}

    /** The state of a try-finally in progress: how its finally block was entered, and where it resumes. */
    private record FinallyContext(String completion, String exception, String resume, int block, Region outer) {}

    /** Where a break or continue goes. {@code breakable} is set for loops and switches, which a plain break can leave. */
    private record Target(List<String> labels, int breakBlock, int continueBlock, int finallyDepth, boolean breakable) {}

    Generators(String file) {
        this.file = file;
    }

    /**
     * Rewrites {@code body}, whose first {@code prologue} statements set up the parameters and must run when the
     * generator function is called, so the generator's statements start after them.
     */
    List<Stmt> transform(List<String> params, List<Stmt> body, int prologue, int line) {
        this.line = line;
        List<Stmt> outer = new ArrayList<>(body.subList(0, prologue));
        List<Stmt> rest = body.subList(prologue, body.size());

        Set<String> functionScope = new HashSet<>(params);
        for (Stmt s : body.subList(0, prologue)) {
            if (s instanceof VarDecl d) {
                d.declarators().forEach(decl -> functionScope.add(decl.name()));
            }
        }
        scopes.push(functionScope);
        Set<String> vars = new LinkedHashSet<>();
        collectVars(rest, vars);
        for (String v : vars) {
            if (!functionScope.contains(v)) {
                hoisted.add(v);
            }
            functionScope.add(v);
        }

        newBlock(null);
        current = 0;
        for (Stmt s : rest) {
            explode(s);
        }
        emitTerminal(new Return(null, line));
        return finish(outer);
    }

    private List<Stmt> finish(List<Stmt> outer) {
        List<Case> cases = new ArrayList<>();
        for (int i = 0; i < blocks.size(); i++) {
            cases.add(new Case(num(i), blocks.get(i)));
        }
        List<Stmt> loopBody = new ArrayList<>();
        Stmt dispatch = new Switch(id(STATE), cases, line);
        if (regions.isEmpty()) {
            loopBody.add(dispatch);
        } else {
            loopBody.add(new Try(new Block(List.of(dispatch), line), CAUGHT, new Block(exceptionDispatch(), line), null, line));
        }
        Stmt machine = new Labeled(LOOP, new While(new Lit(Literal.TRUE, line), new Block(loopBody, line), line), line);
        Function resume = new Function(null, List.of(MODE, ARG), List.of(machine), true, line);

        if (!hoisted.isEmpty()) {
            List<Declarator> declarators = new ArrayList<>();
            hoisted.add(STATE);
            hoisted.add(RETURN_VALUE);
            for (String name : hoisted) {
                declarators.add(new Declarator(name, name.equals(STATE) ? num(0) : null));
            }
            outer.add(new VarDecl("var", declarators, line));
        } else {
            outer.add(new VarDecl("var", List.of(new Declarator(STATE, num(0)), new Declarator(RETURN_VALUE, null)), line));
        }
        outer.addAll(functionDeclarations);
        outer.add(new Return(new Internal("generator", List.of(new FuncExpr(resume, line)), line), line));
        return outer;
    }

    /** The catch handler of the resume function: send the exception to the innermost region the current block is in. */
    private List<Stmt> exceptionDispatch() {
        List<Case> cases = new ArrayList<>();
        for (Region r : regions) {
            List<Integer> inside = new ArrayList<>();
            for (int b = 0; b < blocks.size(); b++) {
                if (blockRegion.get(b) == r) {
                    inside.add(b);
                }
            }
            for (int i = 0; i < inside.size(); i++) {
                List<Stmt> body = new ArrayList<>();
                if (i == inside.size() - 1) {
                    if (r.catches()) {
                        body.add(assign(r.caughtVar(), id(CAUGHT)));
                    } else {
                        body.add(assign(r.completionVar(), num(1)));
                        body.add(assign(r.exceptionVar(), id(CAUGHT)));
                    }
                    body.add(jump(r.handler()));
                }
                cases.add(new Case(num(inside.get(i)), body));
            }
        }
        cases.add(new Case(null, List.of(new Throw(id(CAUGHT), line))));
        return List.of(new Switch(id(STATE), cases, line));
    }

    // ---- building blocks of code ----

    private Expr id(String name) {
        return new Ident(name, line);
    }

    private Expr num(long n) {
        return new Num(n, line);
    }

    private Expr undef() {
        return new Lit(Literal.UNDEFINED, line);
    }

    private Stmt assign(String name, Expr value) {
        return new ExprStmt(new Assign("=", id(name), value, line), line);
    }

    private Expr internal(String name, Expr... args) {
        return new Internal(name, List.of(args), line);
    }

    private Stmt jump(int block) {
        return new Block(List.of(assign(STATE, num(block)), new Continue(LOOP, line)), line);
    }

    private Stmt when(Expr test, Stmt then) {
        return new If(test, then, null, line);
    }

    private Expr is(Expr a, long n) {
        return new Binary("===", a, num(n), line);
    }

    private int newBlock(Region r) {
        blocks.add(new ArrayList<>());
        blockRegion.add(r);
        terminated.add(false);
        return blocks.size() - 1;
    }

    private int newBlock() {
        return newBlock(region);
    }

    private void emit(Stmt s) {
        blocks.get(current).add(s);
    }

    /** Emits a statement that leaves the block, so nothing more is added to it. */
    private void emitTerminal(Stmt s) {
        emit(s);
        terminated.set(current, true);
    }

    private void emitJump(int block) {
        emitTerminal(jump(block));
    }

    /** Ends the current block with a jump to {@code next} unless it already ends, then makes {@code next} current. */
    private void startBlock(int next) {
        if (!terminated.get(current)) {
            emitJump(next);
        }
        current = next;
    }

    private void hoist(String name, boolean lexical) {
        if (lexical) {
            for (Set<String> scope : scopes) {
                if (scope != scopes.peek() && scope.contains(name)) {
                    throw new JsException(file, line, "a generator can't redeclare '" + name
                            + "' from an enclosing block yet");
                }
            }
        }
        scopes.peek().add(name);
        hoisted.add(name);
    }

    private String temp() {
        String name = "$g" + unique++;
        hoisted.add(name);
        return name;
    }

    // ---- what contains a yield ----

    private boolean yields(Stmt s) {
        return switch (s) {
            case VarDecl d -> d.declarators().stream().anyMatch(x -> x.init() != null && yields(x.init()));
            case ExprStmt e -> yields(e.expr());
            case If i -> yields(i.test()) || yields(i.then()) || i.otherwise() != null && yields(i.otherwise());
            case While w -> yields(w.test()) || yields(w.body());
            case DoWhile w -> yields(w.body()) || yields(w.test());
            case For f -> f.init() != null && yields(f.init()) || f.test() != null && yields(f.test())
                    || f.update() != null && yields(f.update()) || yields(f.body());
            case ForOf f -> yields(f.iterable()) || yields(f.body());
            case Block b -> b.body().stream().anyMatch(this::yields);
            case Return r -> r.value() != null && yields(r.value());
            case Throw t -> yields(t.value());
            case Try t -> yields(t.block()) || t.handler() != null && yields(t.handler())
                    || t.finalizer() != null && yields(t.finalizer());
            case Labeled l -> yields(l.body());
            case Switch sw -> yields(sw.discriminant()) || sw.cases().stream().anyMatch(c ->
                    c.test() != null && yields(c.test()) || c.body().stream().anyMatch(this::yields));
            case Break b -> false;
            case Continue c -> false;
            case FunctionDecl f -> false;
            case ClassDecl c -> false;
            case Empty e -> false;
        };
    }

    private boolean yields(Expr e) {
        return switch (e) {
            case Yield y -> true;
            case Num n -> false;
            case Str s -> false;
            case Lit l -> false;
            case This t -> false;
            case Ident i -> false;
            case Hole h -> false;
            case FuncExpr f -> false;
            case ClassExpr c -> false;
            case Template t -> yields(t.exprs());
            case ArrayLit a -> yields(a.elements());
            case ObjectLit o -> o.properties().stream().anyMatch(p -> p.computed() != null && yields(p.computed())
                    || yields(p.value()));
            case Unary u -> yields(u.operand());
            case Update u -> yields(u.target());
            case Binary b -> yields(b.left()) || yields(b.right());
            case Logical l -> yields(l.left()) || yields(l.right());
            case Assign a -> yields(a.target()) || yields(a.value());
            case Conditional c -> yields(c.test()) || yields(c.then()) || yields(c.otherwise());
            case Call c -> yields(c.callee()) || yields(c.args());
            case New n -> yields(n.callee()) || yields(n.args());
            case Member m -> yields(m.object());
            case Index i -> yields(i.object()) || yields(i.index());
            case Sequence s -> yields(s.exprs());
            case Chain c -> yields(c.base()) || c.ops().stream().anyMatch(op ->
                    op.index() != null && yields(op.index()) || op.args() != null && yields(op.args()));
            case Spread s -> yields(s.value());
            case TaggedTemplate t -> yields(t.tag()) || yields(t.exprs());
            case Internal i -> yields(i.args());
            case SuperCall s -> yields(s.args());
            case SuperMember s -> s.index() != null && yields(s.index());
        };
    }

    private boolean yields(List<Expr> list) {
        return list.stream().anyMatch(this::yields);
    }

    // ---- variables ----

    /** Every {@code var} name the statements declare, wherever they are (but not inside nested functions). */
    private void collectVars(List<Stmt> body, Set<String> out) {
        for (Stmt s : body) {
            collectVars(s, out);
        }
    }

    private void collectVars(Stmt s, Set<String> out) {
        switch (s) {
            case VarDecl d -> {
                if (d.kind().equals("var")) {
                    d.declarators().forEach(decl -> out.add(decl.name()));
                }
            }
            case If i -> {
                collectVars(i.then(), out);
                if (i.otherwise() != null) {
                    collectVars(i.otherwise(), out);
                }
            }
            case While w -> collectVars(w.body(), out);
            case DoWhile w -> collectVars(w.body(), out);
            case For f -> {
                if (f.init() != null) {
                    collectVars(f.init(), out);
                }
                collectVars(f.body(), out);
            }
            case ForOf f -> {
                if (f.kind().equals("var")) {
                    out.add(f.name());
                }
                collectVars(f.body(), out);
            }
            case Block b -> collectVars(b.body(), out);
            case Try t -> {
                collectVars(t.block(), out);
                if (t.handler() != null) {
                    collectVars(t.handler(), out);
                }
                if (t.finalizer() != null) {
                    collectVars(t.finalizer(), out);
                }
            }
            case Labeled l -> collectVars(l.body(), out);
            case Switch sw -> sw.cases().forEach(c -> collectVars(c.body(), out));
            default -> { }
        }
    }

    // ---- statements ----

    /** Adds {@code s} to the machine, taking it apart if it contains a yield. */
    private void explode(Stmt s) {
        if (s instanceof FunctionDecl f) {
            // Function declarations are hoisted anyway; they can live in the outer function.
            functionDeclarations.add(f);
            scopes.peek().add(f.function().name());
            return;
        }
        if (s instanceof VarDecl d && !d.kind().equals("var")) {
            // let and const at this level are hoisted so later blocks still see them.
            for (Declarator decl : d.declarators()) {
                hoist(decl.name(), true);
                emit(assign(decl.name(), decl.init() == null ? undef() : ex(decl.init())));
            }
            return;
        }
        if (s instanceof ClassDecl c) {
            hoist(c.name(), true);
            emit(assign(c.name(), c.cls()));
            return;
        }
        if (!yields(s)) {
            emit(simple(s));
            return;
        }
        switch (s) {
            case ExprStmt e -> emit(new ExprStmt(ex(e.expr()), e.line()));
            case VarDecl d -> {
                for (Declarator decl : d.declarators()) {
                    emit(assign(decl.name(), decl.init() == null ? undef() : ex(decl.init())));
                }
            }
            case Return r -> {
                Expr value = r.value() == null ? undef() : ex(r.value());
                emitTerminal(returnStatement(value));
            }
            case Throw t -> emitTerminal(new Throw(ex(t.value()), t.line()));
            case If i -> {
                int otherwise = newBlock();
                int end = newBlock();
                emit(when(new Unary("!", ex(i.test()), line), jump(otherwise)));
                explode(i.then());
                emitJump(end);
                current = otherwise;
                if (i.otherwise() != null) {
                    explode(i.otherwise());
                }
                startBlock(end);
            }
            case While w -> {
                int test = newBlock();
                int end = newBlock();
                startBlock(test);
                emit(when(new Unary("!", ex(w.test()), line), jump(end)));
                targets.push(new Target(takeLabels(), end, test, finallys.size(), true));
                explode(w.body());
                targets.pop();
                emitJump(test);
                current = end;
            }
            case DoWhile w -> {
                int body = newBlock();
                int test = newBlock();
                int end = newBlock();
                startBlock(body);
                targets.push(new Target(takeLabels(), end, test, finallys.size(), true));
                explode(w.body());
                targets.pop();
                startBlock(test);
                emit(when(ex(w.test()), jump(body)));
                startBlock(end);
            }
            case For f -> {
                scopes.push(new HashSet<>());
                if (f.init() != null) {
                    explode(f.init());
                }
                int test = newBlock();
                int update = newBlock();
                int end = newBlock();
                startBlock(test);
                if (f.test() != null) {
                    emit(when(new Unary("!", ex(f.test()), line), jump(end)));
                }
                targets.push(new Target(takeLabels(), end, update, finallys.size(), true));
                explode(f.body());
                targets.pop();
                startBlock(update);
                if (f.update() != null) {
                    emit(new ExprStmt(ex(f.update()), line));
                }
                emitJump(test);
                current = end;
                scopes.pop();
            }
            case ForOf f -> explode(desugarForOf(f));
            case Block b -> {
                scopes.push(new HashSet<>());
                b.body().forEach(this::explode);
                scopes.pop();
            }
            case Labeled l -> {
                Stmt inner = l.body();
                if (inner instanceof While || inner instanceof DoWhile || inner instanceof For || inner instanceof ForOf
                        || inner instanceof Labeled) {
                    pendingLabels.add(l.label());
                    explode(inner);
                } else {
                    int end = newBlock();
                    targets.push(new Target(List.of(l.label()), end, -1, finallys.size(), false));
                    explode(inner);
                    targets.pop();
                    startBlock(end);
                }
            }
            case Switch sw -> explodeSwitch(sw);
            case Try t -> explodeTry(t);
            default -> throw new JsException(file, s.line(), "unsupported statement in a generator");
        }
    }

    private List<String> takeLabels() {
        List<String> labels = new ArrayList<>(pendingLabels);
        pendingLabels.clear();
        return labels;
    }

    /** {@code for (x of it) body} as an iterator loop that closes the iterator however it is left. */
    private Stmt desugarForOf(ForOf f) {
        String keys = temp();
        String index = temp();
        List<Stmt> inner = new ArrayList<>();
        List<Stmt> loop = new ArrayList<>();
        if (f.in()) {
            inner.add(assign(keys, internal("forInKeys", f.iterable())));
            inner.add(assign(index, num(0)));
            Expr more = new Binary("<", id(index), internal("lengthOf", id(keys)), line);
            List<Stmt> body = new ArrayList<>();
            body.add(assign(f.name(), new Index(id(keys), id(index), line)));
            body.add(assign(index, new Binary("+", id(index), num(1), line)));
            body.add(f.body());
            inner.add(new While(more, new Block(body, line), line));
        } else {
            inner.add(assign(keys, internal("iter", f.iterable())));
            List<Stmt> body = new ArrayList<>();
            body.add(assign(f.name(), internal("iterValue", id(keys))));
            body.add(f.body());
            loop.add(new While(internal("iterNext", id(keys)), new Block(body, line), line));
            inner.add(new Try(new Block(loop, line), null, null,
                    new Block(List.of(new ExprStmt(internal("iterClose", id(keys)), line)), line), line));
        }
        if (!f.kind().equals("var")) {
            hoist(f.name(), true);
        }
        // Labels written before the loop stay pending, so they attach to the while loop inside.
        return new Block(inner, f.line());
    }

    private void explodeSwitch(Switch sw) {
        scopes.push(new HashSet<>());
        String value = temp();
        emit(assign(value, ex(sw.discriminant())));
        int end = newBlock();
        List<Integer> bodies = new ArrayList<>();
        for (int i = 0; i < sw.cases().size(); i++) {
            bodies.add(newBlock());
        }
        int defaultBlock = end;
        for (int i = 0; i < sw.cases().size(); i++) {
            Case c = sw.cases().get(i);
            if (c.test() == null) {
                defaultBlock = bodies.get(i);
            } else {
                emit(when(new Binary("===", id(value), ex(c.test()), line), jump(bodies.get(i))));
            }
        }
        emitJump(defaultBlock);
        targets.push(new Target(takeLabels(), end, -1, finallys.size(), true));
        for (int i = 0; i < sw.cases().size(); i++) {
            current = bodies.get(i);
            terminated.set(current, false);
            sw.cases().get(i).body().forEach(this::explode);
            if (i + 1 < sw.cases().size()) {
                startBlock(bodies.get(i + 1));
            }
        }
        targets.pop();
        startBlock(end);
        scopes.pop();
    }

    private void explodeTry(Try t) {
        int n = unique++;
        String caught = "$ce" + n;
        String completion = "$c" + n;
        String exception = "$x" + n;
        String resume = "$k" + n;
        boolean hasCatch = t.handler() != null;
        boolean hasFinally = t.finalizer() != null;
        Region outer = region;
        if (hasCatch) {
            hoisted.add(caught);
        }
        if (hasFinally) {
            hoisted.add(completion);
            hoisted.add(exception);
            hoisted.add(resume);
        }

        int finallyBlock = hasFinally ? newBlock(outer) : -1;
        int end = newBlock(outer);
        FinallyContext context = hasFinally ? new FinallyContext(completion, exception, resume, finallyBlock, outer) : null;
        Region finallyRegion = outer;
        if (hasFinally) {
            finallyRegion = new Region(outer, false, finallyBlock, null, completion, exception);
            regions.add(finallyRegion);
            region = finallyRegion;
            finallys.add(context);
        }
        int catchBlock = -1;
        Region catchRegion = null;
        if (hasCatch) {
            catchBlock = newBlock(finallyRegion);
            catchRegion = new Region(finallyRegion, true, catchBlock, caught, null, null);
            regions.add(catchRegion);
        }

        int body = newBlock(hasCatch ? catchRegion : finallyRegion);
        startBlock(body);
        region = hasCatch ? catchRegion : finallyRegion;
        explode(t.block());
        region = finallyRegion;
        leaveTry(hasFinally, finallyBlock, completion, end);
        if (hasCatch) {
            current = catchBlock;
            terminated.set(current, false);
            scopes.push(new HashSet<>());
            if (t.param() != null) {
                hoist(t.param(), true);
                emit(assign(t.param(), id(caught)));
            }
            explode(t.handler());
            scopes.pop();
            leaveTry(hasFinally, finallyBlock, completion, end);
        }
        if (hasFinally) {
            finallys.remove(finallys.size() - 1);
            region = outer;
            current = finallyBlock;
            terminated.set(current, false);
            explode(t.finalizer());
            emit(when(is(id(completion), 1), new Throw(id(exception), line)));
            emit(when(is(id(completion), 2), new Block(List.of(assign(STATE, id(resume)), new Continue(LOOP, line)), line)));
            startBlock(end);
        } else {
            region = outer;
            current = end;
        }
    }

    /** The end of a try body or catch body: on to the finally block (marked as a normal completion) or past the statement. */
    private void leaveTry(boolean hasFinally, int finallyBlock, String completion, int end) {
        if (hasFinally) {
            emit(assign(completion, num(0)));
            emitJump(finallyBlock);
        } else {
            emitJump(end);
        }
    }

    // ---- jumps through finally blocks ----

    /** Leaves toward {@code target}, running the finally blocks in {@code chain} (innermost first) on the way. */
    private Stmt jumpThrough(List<FinallyContext> chain, int target) {
        if (chain.isEmpty()) {
            return jump(target);
        }
        FinallyContext f = chain.get(0);
        int trampoline = newBlock(f.outer());
        int saved = current;
        current = trampoline;
        emitTerminal(jumpThrough(chain.subList(1, chain.size()), target));
        current = saved;
        return new Block(List.of(assign(f.completion(), num(2)), assign(f.resume(), num(trampoline)), jump(f.block())), line);
    }

    private List<FinallyContext> pendingFinallys(int depth) {
        List<FinallyContext> chain = new ArrayList<>();
        for (int i = finallys.size() - 1; i >= depth; i--) {
            chain.add(finallys.get(i));
        }
        return chain;
    }

    /** {@code return value} from inside the machine: straight out, or through every pending finally first. */
    private Stmt returnStatement(Expr value) {
        if (finallys.isEmpty()) {
            return new Return(value, line);
        }
        if (returnBlock < 0) {
            returnBlock = newBlock(null);
            int saved = current;
            current = returnBlock;
            emitTerminal(new Return(id(RETURN_VALUE), line));
            current = saved;
        }
        return new Block(List.of(assign(RETURN_VALUE, value), jumpThrough(pendingFinallys(0), returnBlock)), line);
    }

    private Target findTarget(String label, boolean forContinue, int atLine) {
        for (Target t : targets) {
            boolean matches = label != null ? t.labels().contains(label) && (!forContinue || t.continueBlock() >= 0)
                    : forContinue ? t.continueBlock() >= 0 : t.breakable();
            if (matches) {
                return t;
            }
        }
        throw new JsException(file, atLine, label != null ? "undefined label '" + label + "'"
                : forContinue ? "continue outside a loop" : "break outside a loop or switch");
    }

    // ---- statements without a yield ----

    /** A yield-free statement, with the var declarations it holds turned into assignments and escaping jumps rewritten. */
    private Stmt simple(Stmt s) {
        return rewrite(s, 0, 0, List.of());
    }

    private Stmt rewrite(Stmt s, int breaks, int continues, List<String> labels) {
        switch (s) {
            case VarDecl d -> {
                return d.kind().equals("var") ? varToAssignments(d) : s;
            }
            case If i -> {
                return new If(i.test(), rewrite(i.then(), breaks, continues, labels),
                        i.otherwise() == null ? null : rewrite(i.otherwise(), breaks, continues, labels), i.line());
            }
            case While w -> {
                return new While(w.test(), rewrite(w.body(), breaks + 1, continues + 1, labels), w.line());
            }
            case DoWhile w -> {
                return new DoWhile(rewrite(w.body(), breaks + 1, continues + 1, labels), w.test(), w.line());
            }
            case For f -> {
                Stmt init = f.init() == null ? null : rewrite(f.init(), breaks, continues, labels);
                return new For(init, f.test(), f.update(), rewrite(f.body(), breaks + 1, continues + 1, labels), f.line());
            }
            case ForOf f -> {
                Stmt body = rewrite(f.body(), breaks + 1, continues + 1, labels);
                if (f.kind().equals("var")) {
                    String hiddenName = "$h" + unique++;
                    return new ForOf("let", hiddenName, f.iterable(),
                            new Block(List.of(assign(f.name(), id(hiddenName)), body), f.line()), f.line(), f.in());
                }
                return new ForOf(f.kind(), f.name(), f.iterable(), body, f.line(), f.in());
            }
            case Block b -> {
                List<Stmt> out = new ArrayList<>();
                for (Stmt inner : b.body()) {
                    out.add(rewrite(inner, breaks, continues, labels));
                }
                return new Block(out, b.line());
            }
            case Labeled l -> {
                List<String> more = new ArrayList<>(labels);
                more.add(l.label());
                return new Labeled(l.label(), rewrite(l.body(), breaks, continues, more), l.line());
            }
            case Switch sw -> {
                List<Case> cases = new ArrayList<>();
                for (Case c : sw.cases()) {
                    List<Stmt> body = new ArrayList<>();
                    for (Stmt inner : c.body()) {
                        body.add(rewrite(inner, breaks + 1, continues, labels));
                    }
                    cases.add(new Case(c.test(), body));
                }
                return new Switch(sw.discriminant(), cases, sw.line());
            }
            case Try t -> {
                return new Try((Block) rewrite(t.block(), breaks, continues, labels), t.param(),
                        t.handler() == null ? null : (Block) rewrite(t.handler(), breaks, continues, labels),
                        t.finalizer() == null ? null : (Block) rewrite(t.finalizer(), breaks, continues, labels), t.line());
            }
            case Return r -> {
                return finallys.isEmpty() ? s : returnStatement(r.value() == null ? undef() : r.value());
            }
            case Break b -> {
                boolean local = b.label() == null ? breaks > 0 : labels.contains(b.label());
                if (local) {
                    return s;
                }
                Target t = findTarget(b.label(), false, b.line());
                return jumpThrough(pendingFinallys(t.finallyDepth()), t.breakBlock());
            }
            case Continue c -> {
                boolean local = c.label() == null ? continues > 0 : labels.contains(c.label());
                if (local) {
                    return s;
                }
                Target t = findTarget(c.label(), true, c.line());
                return jumpThrough(pendingFinallys(t.finallyDepth()), t.continueBlock());
            }
            default -> {
                return s;
            }
        }
    }

    /** {@code var a = 1, b = 2;} where the names are already hoisted becomes {@code a = 1, b = 2;}. */
    private Stmt varToAssignments(VarDecl d) {
        List<Expr> steps = new ArrayList<>();
        for (Declarator decl : d.declarators()) {
            if (decl.init() != null) {
                steps.add(new Assign("=", id(decl.name()), decl.init(), d.line()));
            }
        }
        if (steps.isEmpty()) {
            return new Empty(d.line());
        }
        return new ExprStmt(steps.size() == 1 ? steps.get(0) : new Sequence(steps, d.line()), d.line());
    }

    // ---- expressions ----

    /**
     * Returns an expression equivalent to {@code e} that contains no yield, after emitting the statements that
     * evaluate everything that did, in order, into temporaries.
     */
    private Expr ex(Expr e) {
        if (!yields(e)) {
            return e;
        }
        switch (e) {
            case Yield y -> {
                return yieldValue(y);
            }
            case Template t -> {
                return new Template(t.chunks(), exList(t.exprs()), t.line());
            }
            case ArrayLit a -> {
                return new ArrayLit(exList(a.elements()), a.line());
            }
            case ObjectLit o -> {
                List<Expr> parts = new ArrayList<>();
                for (Property p : o.properties()) {
                    if (p.computed() != null) {
                        parts.add(p.computed());
                    }
                    parts.add(p.value());
                }
                List<Expr> done = exList(parts);
                List<Property> out = new ArrayList<>();
                int at = 0;
                for (Property p : o.properties()) {
                    Expr computed = p.computed() != null ? done.get(at++) : null;
                    out.add(new Property(p.key(), computed, done.get(at++), p.kind()));
                }
                return new ObjectLit(out, o.line());
            }
            case Unary u -> {
                return new Unary(u.op(), ex(u.operand()), u.line());
            }
            case Binary b -> {
                List<Expr> two = exList(List.of(b.left(), b.right()));
                return new Binary(b.op(), two.get(0), two.get(1), b.line());
            }
            case Logical l -> {
                if (!yields(l.right())) {
                    return new Logical(l.op(), ex(l.left()), l.right(), l.line());
                }
                String result = temp();
                emit(assign(result, ex(l.left())));
                int evaluate = newBlock();
                int end = newBlock();
                Expr skip = switch (l.op()) {
                    case "&&" -> new Unary("!", id(result), l.line());
                    case "||" -> id(result);
                    default -> new Unary("!", new Binary("==", id(result), new Lit(Literal.NULL, l.line()), l.line()), l.line());
                };
                emit(when(skip, jump(end)));
                emitJump(evaluate);
                current = evaluate;
                terminated.set(current, false);
                emit(assign(result, ex(l.right())));
                startBlock(end);
                return id(result);
            }
            case Conditional c -> {
                if (!yields(c.then()) && !yields(c.otherwise())) {
                    return new Conditional(ex(c.test()), c.then(), c.otherwise(), c.line());
                }
                String result = temp();
                int otherwise = newBlock();
                int end = newBlock();
                emit(when(new Unary("!", ex(c.test()), line), jump(otherwise)));
                emit(assign(result, ex(c.then())));
                emitJump(end);
                current = otherwise;
                terminated.set(current, false);
                emit(assign(result, ex(c.otherwise())));
                startBlock(end);
                return id(result);
            }
            case Assign a -> {
                return exAssign(a);
            }
            case Update u -> {
                return new Update(u.op(), u.prefix(), exTarget(u.target()), u.line());
            }
            case Call c -> {
                if (c.callee() instanceof Member m) {
                    Expr object = spill(ex(m.object()));
                    return new Call(new Member(object, m.name(), m.line()), exList(c.args()), c.line());
                }
                if (c.callee() instanceof Index i) {
                    List<Expr> both = exList(List.of(i.object(), i.index()));
                    String object = temp();
                    // The receiver is evaluated once into a temporary so the call keeps its this.
                    emit(assign(object, both.get(0)));
                    return new Call(new Index(id(object), both.get(1), i.line()), exList(c.args()), c.line());
                }
                List<Expr> all = new ArrayList<>();
                all.add(c.callee());
                all.addAll(c.args());
                List<Expr> done = exList(all);
                return new Call(done.get(0), done.subList(1, done.size()), c.line());
            }
            case New n -> {
                List<Expr> all = new ArrayList<>();
                all.add(n.callee());
                all.addAll(n.args());
                List<Expr> done = exList(all);
                return new New(done.get(0), done.subList(1, done.size()), n.line());
            }
            case Member m -> {
                return new Member(ex(m.object()), m.name(), m.line());
            }
            case Index i -> {
                List<Expr> both = exList(List.of(i.object(), i.index()));
                return new Index(both.get(0), both.get(1), i.line());
            }
            case Sequence s -> {
                for (int i = 0; i < s.exprs().size() - 1; i++) {
                    emit(new ExprStmt(ex(s.exprs().get(i)), s.line()));
                }
                return ex(s.exprs().get(s.exprs().size() - 1));
            }
            case Spread s -> {
                return new Spread(ex(s.value()), s.line());
            }
            case TaggedTemplate t -> {
                List<Expr> all = new ArrayList<>();
                all.add(t.tag());
                all.addAll(t.exprs());
                List<Expr> done = exList(all);
                return new TaggedTemplate(done.get(0), t.cooked(), t.raw(), done.subList(1, done.size()), t.line());
            }
            case Internal i -> {
                return new Internal(i.name(), exList(i.args()), i.line());
            }
            case SuperCall s -> {
                return new SuperCall(exList(s.args()), s.line());
            }
            default -> throw new JsException(file, e.line(), "yield isn't supported inside this kind of expression yet");
        }
    }

    /** Evaluates the expressions in order; those before the last yield are saved in temporaries first. */
    private List<Expr> exList(List<Expr> list) {
        int last = -1;
        for (int i = 0; i < list.size(); i++) {
            if (yields(list.get(i))) {
                last = i;
            }
        }
        List<Expr> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            Expr item = list.get(i);
            out.add(i < last ? spill(ex(item)) : ex(item));
        }
        return out;
    }

    /** Saves a value so a later yield can't change what it means. */
    private Expr spill(Expr value) {
        if (value instanceof Num || value instanceof Str || value instanceof Lit) {
            return value;
        }
        if (value instanceof Spread sp) {
            return new Spread(spill(sp.value()), sp.line());
        }
        String name = temp();
        emit(assign(name, value));
        return id(name);
    }

    private Expr exTarget(Expr target) {
        if (target instanceof Member m) {
            return new Member(spill(ex(m.object())), m.name(), m.line());
        }
        if (target instanceof Index i) {
            List<Expr> both = exList(List.of(i.object(), i.index()));
            return new Index(spill(both.get(0)), spill(both.get(1)), i.line());
        }
        return target;
    }

    private Expr exAssign(Assign a) {
        String op = a.op();
        if (op.equals("&&=") || op.equals("||=") || op.equals("??=")) {
            throw new JsException(file, a.line(), "logical assignment with a yield isn't supported yet");
        }
        Expr target = exTarget(a.target());
        if (op.equals("=")) {
            return new Assign("=", target, ex(a.value()), a.line());
        }
        // x op= (yield v): read x first, then yield, then store.
        String old = temp();
        emit(assign(old, target));
        Expr value = ex(a.value());
        return new Assign("=", target, new Binary(op.substring(0, op.length() - 1), id(old), value, a.line()), a.line());
    }

    /** {@code yield value}: hand the value out, and when resumed deal with a throw or return before using what was sent. */
    private Expr yieldValue(Yield y) {
        Expr value = y.value() == null ? undef() : ex(y.value());
        if (y.delegate()) {
            return delegate(value);
        }
        int resume = newBlock();
        emit(assign(STATE, num(resume)));
        emitTerminal(new Return(internal("yielded", value), line));
        current = resume;
        resumeChecks();
        String sent = temp();
        emit(assign(sent, id(ARG)));
        return id(sent);
    }

    /** What happens on resuming: a throw() is thrown here, and a return() leaves the generator through its finally blocks. */
    private void resumeChecks() {
        emit(when(is(id(MODE), 1), new Throw(id(ARG), line)));
        emit(when(is(id(MODE), 2), returnStatement(id(ARG))));
    }

    /** {@code yield* iterable}: pass each value out, and forward what the caller sends back in. */
    private Expr delegate(Expr iterable) {
        String iterator = temp();
        String mode = temp();
        String sent = temp();
        String result = temp();
        String value = temp();
        emit(assign(iterator, internal("delegate", iterable)));
        emit(assign(mode, num(0)));
        emit(assign(sent, undef()));
        int loop = newBlock();
        int end = newBlock();
        startBlock(loop);
        emit(assign(result, internal("delegateStep", id(iterator), id(mode), id(sent))));
        emit(assign(value, new Member(id(result), "value", line)));
        emit(when(new Member(id(result), "done", line), new Block(List.of(
                when(is(id(mode), 2), returnStatement(id(value))),
                jump(end)), line)));
        int resume = newBlock();
        emit(assign(STATE, num(resume)));
        emitTerminal(new Return(internal("yielded", id(value)), line));
        current = resume;
        emit(assign(mode, id(MODE)));
        emit(assign(sent, id(ARG)));
        emitJump(loop);
        current = end;
        terminated.set(current, false);
        return id(value);
    }
}
