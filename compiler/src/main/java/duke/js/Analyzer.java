package duke.js;

import duke.js.Node.*;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves every name to a variable, decides which variables closures capture, and assigns frame
 * slots. Code generation then never has to look anything up.
 *
 * <p>Top-level variables of the prelude and of the program are globals: fixed symbols in .data, so
 * closures never need to capture them. Everything else lives in its function's frame, and a
 * variable some inner function reads or writes is boxed so the closure and the frame share it.
 */
final class Analyzer {

    /** Names the code generator expands inline instead of calling. */
    static final Set<String> INTRINSICS = Set.of("__syscall", "__peek", "__poke", "__peekByte", "__pokeByte",
            "__addr", "__ptr", "__alloc", "__copy", "__arg", "__argc", "__isInt", "__isPtr", "__identical");

    /** Frame slots before the first variable: the closure, the incoming {@code this} and the argument count. */
    static final int CLOSURE_SLOT = 0;
    static final int THIS_SLOT = 1;
    static final int ARGC_SLOT = 2;
    private static final int FIRST_VARIABLE_SLOT = 3;

    static final class Var {
        final String name;
        final String kind;
        final FuncInfo owner;
        final boolean global;
        boolean captured;
        int slot = -1;
        String symbol;
        /** Set for a function declaration: the function this variable is initialized to. */
        Function declared;

        Var(String name, String kind, FuncInfo owner, boolean global) {
            this.name = name;
            this.kind = kind;
            this.owner = owner;
            this.global = global;
        }

        boolean boxed() {
            return captured && !global;
        }
    }

    static final class FuncInfo {
        final Function node;
        final FuncInfo parent;
        final boolean main;
        final List<Var> captures = new ArrayList<>();
        Scope scope;
        int slots = FIRST_VARIABLE_SLOT;
        String symbol;

        FuncInfo(Function node, FuncInfo parent, boolean main) {
            this.node = node;
            this.parent = parent;
            this.main = main;
        }

        int newSlot() {
            return slots++;
        }
    }

    static final class Scope {
        final Scope parent;
        final FuncInfo func;
        final boolean topLevel;
        final Map<String, Var> vars = new LinkedHashMap<>();
        final List<FunctionDecl> hoisted = new ArrayList<>();

        Scope(Scope parent, FuncInfo func, boolean topLevel) {
            this.parent = parent;
            this.func = func;
            this.topLevel = topLevel;
        }
    }

    private final String file;
    private final Set<String> externals;
    private final Scope preludeScope;
    private final FuncInfo main;
    private int symbolCounter;

    final Map<Object, Scope> scopes = new IdentityHashMap<>();
    final Map<Object, Var> resolved = new IdentityHashMap<>();
    final Map<Function, FuncInfo> functions = new IdentityHashMap<>();
    final Map<ForOf, int[]> iterationSlots = new IdentityHashMap<>();
    final List<Var> globals = new ArrayList<>();

    private Scope current;

    Analyzer(String file, List<Stmt> prelude, List<Stmt> program) {
        this(file, prelude, program, INTRINSICS);
    }

    /** {@code externals} are names that need no declaration: intrinsics, or globals the runtime provides. */
    Analyzer(String file, List<Stmt> prelude, List<Stmt> program, Set<String> externals) {
        this.file = file;
        this.externals = externals;
        this.main = new FuncInfo(new Function("main", List.of(), List.of(), false, 0), null, true);
        main.symbol = "fn.main";
        this.preludeScope = new Scope(null, main, true);
        Scope userScope = new Scope(preludeScope, main, true);
        // Both units run in one frame, prelude first. Only the program's scope is the function's own.
        main.scope = userScope;
        analyzeUnit(prelude, preludeScope);
        analyzeUnit(program, userScope);
    }

    FuncInfo main() {
        return main;
    }

    /** The prelude's statements run in the same frame as the program's, before them. */
    Scope preludeScope() {
        return preludeScope;
    }

    private void analyzeUnit(List<Stmt> body, Scope scope) {
        current = scope;
        declareBlock(body, scope);
        for (Stmt s : body) {
            visit(s);
        }
    }

    // ---- declarations ----

    private Var declare(Scope scope, String name, String kind, int line) {
        Var existing = scope.vars.get(name);
        if (existing != null) {
            Set<String> plain = Set.of("var", "function", "param", "self");
            boolean redeclarable = plain.contains(kind) && plain.contains(existing.kind);
            if (!redeclarable) {
                throw new JsException(file, line, "Identifier '" + name + "' has already been declared");
            }
            return existing;
        }
        Var v = new Var(name, kind, scope.func, scope.topLevel);
        if (v.global) {
            v.symbol = (scope == preludeScope ? "g." : "u.") + name;
            globals.add(v);
        } else {
            v.slot = scope.func.newSlot();
        }
        scope.vars.put(name, v);
        return v;
    }

    /** The function-level scope that {@code var} declarations hoist to. */
    private Scope functionScope(Scope s) {
        while (s.parent != null && s.parent.func == s.func && !s.topLevel) {
            s = s.parent;
        }
        return s;
    }

    private void declareBlock(List<Stmt> body, Scope scope) {
        for (Stmt s : body) {
            switch (s) {
                case VarDecl d when !d.kind().equals("var") -> {
                    for (Declarator decl : d.declarators()) {
                        resolved.put(decl, declare(scope, decl.name(), d.kind(), d.line()));
                    }
                }
                case ClassDecl c -> resolved.put(c, declare(scope, c.name(), "let", c.line()));
                case FunctionDecl f -> {
                    Var v = declare(scope, f.function().name(), "function", f.line());
                    v.declared = f.function();
                    scope.hoisted.add(f);
                    resolved.put(f, v);
                }
                default -> { }
            }
            hoistVars(s, functionScope(scope));
        }
    }

    /** Declares {@code var}s found anywhere inside {@code s}, except in nested functions. */
    private void hoistVars(Stmt s, Scope fnScope) {
        switch (s) {
            case VarDecl d when d.kind().equals("var") -> {
                for (Declarator decl : d.declarators()) {
                    resolved.put(decl, declare(fnScope, decl.name(), "var", d.line()));
                }
            }
            case If i -> {
                hoistVars(i.then(), fnScope);
                if (i.otherwise() != null) {
                    hoistVars(i.otherwise(), fnScope);
                }
            }
            case While w -> hoistVars(w.body(), fnScope);
            case DoWhile w -> hoistVars(w.body(), fnScope);
            case For f -> {
                if (f.init() != null) {
                    hoistVars(f.init(), fnScope);
                }
                hoistVars(f.body(), fnScope);
            }
            case ForOf f -> {
                if (f.kind().equals("var")) {
                    resolved.put(f, declare(fnScope, f.name(), "var", f.line()));
                }
                hoistVars(f.body(), fnScope);
            }
            case Block b -> {
                for (Stmt inner : b.body()) {
                    hoistVars(inner, fnScope);
                }
            }
            case Labeled l -> hoistVars(l.body(), fnScope);
            case Switch sw -> {
                for (Case c : sw.cases()) {
                    for (Stmt inner : c.body()) {
                        hoistVars(inner, fnScope);
                    }
                }
            }
            case Try t -> {
                hoistVars(t.block(), fnScope);
                if (t.handler() != null) {
                    hoistVars(t.handler(), fnScope);
                }
                if (t.finalizer() != null) {
                    hoistVars(t.finalizer(), fnScope);
                }
            }
            default -> { }
        }
    }

    // ---- statements ----

    private void visit(Stmt s) {
        switch (s) {
            case VarDecl d -> {
                for (Declarator decl : d.declarators()) {
                    if (decl.init() != null) {
                        visit(decl.init());
                    }
                }
            }
            case ExprStmt e -> visit(e.expr());
            case If i -> {
                visit(i.test());
                visit(i.then());
                if (i.otherwise() != null) {
                    visit(i.otherwise());
                }
            }
            case While w -> {
                visit(w.test());
                visit(w.body());
            }
            case DoWhile w -> {
                visit(w.body());
                visit(w.test());
            }
            case For f -> inScope(f, () -> {
                if (f.init() != null) {
                    declareBlock(List.of(f.init()), current);
                    visit(f.init());
                }
                if (f.test() != null) {
                    visit(f.test());
                }
                if (f.update() != null) {
                    visit(f.update());
                }
                visit(f.body());
            });
            case ForOf f -> {
                visit(f.iterable());
                inScope(f, () -> {
                    if (!f.kind().equals("var")) {
                        resolved.put(f, declare(current, f.name(), f.kind(), f.line()));
                    }
                    iterationSlots.put(f, new int[] {current.func.newSlot(), current.func.newSlot()});
                    visit(f.body());
                });
            }
            case Block b -> inScope(b, () -> {
                declareBlock(b.body(), current);
                for (Stmt inner : b.body()) {
                    visit(inner);
                }
            });
            case Return r -> {
                if (r.value() != null) {
                    visit(r.value());
                }
            }
            case Labeled l -> visit(l.body());
            case Switch sw -> {
                visit(sw.discriminant());
                // The whole switch body is one scope, so a let in one case is visible in the others.
                inScope(sw, () -> {
                    List<Stmt> all = new ArrayList<>();
                    sw.cases().forEach(c -> all.addAll(c.body()));
                    declareBlock(all, current);
                    for (Case c : sw.cases()) {
                        if (c.test() != null) {
                            visit(c.test());
                        }
                        c.body().forEach(this::visit);
                    }
                });
            }
            case ClassDecl c -> visit(c.cls());
            case Throw t -> visit(t.value());
            case Try t -> {
                visit(t.block());
                if (t.handler() != null) {
                    // The catch parameter lives in a scope of its own around the handler's block.
                    inScope(t, () -> {
                        if (t.param() != null) {
                            resolved.put(t, declare(current, t.param(), "let", t.line()));
                        }
                        visit(t.handler());
                    });
                }
                if (t.finalizer() != null) {
                    visit(t.finalizer());
                }
            }
            case FunctionDecl f -> visitFunction(f.function(), false);
            case Break b -> { }
            case Continue c -> { }
            case Empty e -> { }
        }
    }

    private void inScope(Object node, Runnable body) {
        Scope scope = new Scope(current, current.func, false);
        scopes.put(node, scope);
        Scope saved = current;
        current = scope;
        body.run();
        current = saved;
    }

    private void visitFunction(Function fn, boolean expression) {
        FuncInfo info = new FuncInfo(fn, current.func, false);
        info.symbol = "fn." + symbolCounter++ + (fn.name() == null ? "" : "." + fn.name());
        Scope scope = new Scope(current, info, false);
        info.scope = scope;
        functions.put(fn, info);
        scopes.put(fn, scope);
        Scope saved = current;
        current = scope;
        for (String p : fn.params()) {
            declare(scope, p, "param", fn.line());
        }
        if (expression && fn.name() != null && !fn.arrow() && !scope.vars.containsKey(fn.name())) {
            // A named function expression can call itself by name; the prologue binds it to the closure.
            declare(scope, fn.name(), "self", fn.line());
        }
        declareBlock(fn.body(), scope);
        for (Stmt s : fn.body()) {
            visit(s);
        }
        current = saved;
    }

    // ---- expressions ----

    private void visit(Expr e) {
        switch (e) {
            case Num n -> { }
            case Str s -> { }
            case Lit l -> { }
            case Template t -> t.exprs().forEach(this::visit);
            case This t -> {
                Var v = thisVar();
                if (v != null) {
                    use(v);
                    resolved.put(t, v);
                }
            }
            case Ident id -> {
                Var v = lookup(id.name());
                if (v == null && id.name().equals("arguments")) {
                    v = argumentsVar();
                }
                if (v == null) {
                    if (!externals.contains(id.name())) {
                        throw new JsException(file, id.line(), id.name() + " is not defined");
                    }
                } else {
                    use(v);
                    resolved.put(id, v);
                }
            }
            case ArrayLit a -> a.elements().forEach(this::visit);
            case ObjectLit o -> o.properties().forEach(p -> {
                if (p.computed() != null) {
                    visit(p.computed());
                }
                visit(p.value());
            });
            case Hole h -> { }
            case Yield y -> throw new JsException(file, y.line(), "yield is only valid inside a generator");
            case ClassExpr c -> {
                if (c.superclass() != null) {
                    visit(c.superclass());
                }
                for (Function f : new Function[] {c.constructor(), c.fields(), c.statics()}) {
                    if (f != null) {
                        visitFunction(f, false);
                    }
                }
                for (ClassMember m : c.members()) {
                    if (m.computed() != null) {
                        visit(m.computed());
                    }
                    visit(m.value());
                }
            }
            case SuperCall sc -> sc.args().forEach(this::visit);
            case SuperMember sm -> {
                if (sm.index() != null) {
                    visit(sm.index());
                }
            }
            case Spread sp -> visit(sp.value());
            case TaggedTemplate tt -> {
                visit(tt.tag());
                tt.exprs().forEach(this::visit);
            }
            case Internal in -> in.args().forEach(this::visit);
            case FuncExpr f -> visitFunction(f.function(), true);
            case Unary u -> visit(u.operand());
            case Update u -> visit(u.target());
            case Binary b -> {
                visit(b.left());
                visit(b.right());
            }
            case Logical l -> {
                visit(l.left());
                visit(l.right());
            }
            case Assign a -> {
                visit(a.target());
                visit(a.value());
            }
            case Conditional c -> {
                visit(c.test());
                visit(c.then());
                visit(c.otherwise());
            }
            case Call c -> {
                visit(c.callee());
                c.args().forEach(this::visit);
            }
            case Chain c -> {
                visit(c.base());
                for (ChainOp op : c.ops()) {
                    if (op.index() != null) {
                        visit(op.index());
                    }
                    if (op.args() != null) {
                        op.args().forEach(this::visit);
                    }
                }
            }
            case New n -> {
                visit(n.callee());
                n.args().forEach(this::visit);
            }
            case Member m -> visit(m.object());
            case Index i -> {
                visit(i.object());
                visit(i.index());
            }
            case Sequence s -> s.exprs().forEach(this::visit);
        }
    }

    private Var lookup(String name) {
        for (Scope s = current; s != null; s = s.parent) {
            Var v = s.vars.get(name);
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    /** The {@code arguments} of the nearest non-arrow function, declared on first use; null at top level. */
    private Var argumentsVar() {
        FuncInfo f = current.func;
        while (f != null && !f.main && f.node.arrow()) {
            f = f.parent;
        }
        if (f == null || f.main) {
            return null;
        }
        Var v = f.scope.vars.get("arguments");
        if (v == null) {
            v = new Var("arguments", "arguments", f, false);
            v.slot = f.newSlot();
            f.scope.vars.put("arguments", v);
        }
        return v;
    }

    /** The {@code this} of the nearest non-arrow function, declared on first use; null at top level. */
    private Var thisVar() {
        FuncInfo f = current.func;
        while (f != null && !f.main && f.node.arrow()) {
            f = f.parent;
        }
        if (f == null || f.main) {
            return null;
        }
        Var v = f.scope.vars.get("this");
        if (v == null) {
            v = new Var("this", "this", f, false);
            v.slot = f.newSlot();
            f.scope.vars.put("this", v);
        }
        return v;
    }

    /** Marks {@code v} captured by every function between the current one and its owner. */
    private void use(Var v) {
        if (v.global || v.owner == current.func) {
            return;
        }
        v.captured = true;
        for (FuncInfo f = current.func; f != v.owner; f = f.parent) {
            if (!f.captures.contains(v)) {
                f.captures.add(v);
            }
        }
    }
}
