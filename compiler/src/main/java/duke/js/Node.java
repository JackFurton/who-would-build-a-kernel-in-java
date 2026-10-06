package duke.js;

import java.util.List;

/** The syntax tree. Every node that can fail at run time or while compiling keeps its source line. */
final class Node {

    private Node() {
    }

    sealed interface Expr {
        int line();
    }

    record Num(long value, int line) implements Expr {}

    record Str(String value, int line) implements Expr {}

    /** {@code chunks.size() == exprs.size() + 1}. */
    record Template(List<String> chunks, List<Expr> exprs, int line) implements Expr {}

    record Lit(Literal value, int line) implements Expr {}

    enum Literal { TRUE, FALSE, NULL, UNDEFINED }

    record This(int line) implements Expr {}

    record Ident(String name, int line) implements Expr {}

    record ArrayLit(List<Expr> elements, int line) implements Expr {}

    /**
     * {@code key} is the name; {@code computed} is set instead for {@code [expr]: value}; a spread has neither.
     * {@code kind} is 'i' for an ordinary member, 'g' for a getter and 's' for a setter (whose value is the function).
     */
    record Property(String key, Expr computed, Expr value, char kind) {}

    record ObjectLit(List<Property> properties, int line) implements Expr {}

    record FuncExpr(Function function, int line) implements Expr {}

    record Unary(String op, Expr operand, int line) implements Expr {}

    record Update(String op, boolean prefix, Expr target, int line) implements Expr {}

    record Binary(String op, Expr left, Expr right, int line) implements Expr {}

    /** {@code &&}, {@code ||} and {@code ??}: the right side runs only when needed. */
    record Logical(String op, Expr left, Expr right, int line) implements Expr {}

    /** {@code op} is "=" or a compound operator such as "+=". */
    record Assign(String op, Expr target, Expr value, int line) implements Expr {}

    record Conditional(Expr test, Expr then, Expr otherwise, int line) implements Expr {}

    record Call(Expr callee, List<Expr> args, int line) implements Expr {}

    record New(Expr callee, List<Expr> args, int line) implements Expr {}

    record Member(Expr object, String name, int line) implements Expr {}

    record Index(Expr object, Expr index, int line) implements Expr {}

    record Sequence(List<Expr> exprs, int line) implements Expr {}

    /** {@code kind} is 'm' (method), 'g' (getter) or 's' (setter); {@code value} is the function. */
    record ClassMember(char kind, boolean isStatic, String key, Expr computed, Expr value) {}

    /**
     * A class. {@code constructor} is null for a base class without one; fields and static members are already
     * desugared into the {@code fields} function (run on each new instance) and the {@code statics} function (run
     * once, with the class as {@code this}), either of which may be null.
     */
    record ClassExpr(String name, Expr superclass, boolean hasSuper, Function constructor, List<ClassMember> members,
            Function fields, Function statics, int line) implements Expr {}

    /** {@code super(args)} in a constructor. */
    record SuperCall(List<Expr> args, int line) implements Expr {}

    /** {@code super.name} or {@code super[index]}. */
    record SuperMember(String name, Expr index, int line) implements Expr {}

    /** An elision in an array literal or pattern: the gap in {@code [a, , b]}. */
    record Hole(int line) implements Expr {}

    /** {@code ...value} in a call's arguments, an array literal or an object literal. */
    record Spread(Expr value, int line) implements Expr {}

    /** {@code tag`a${b}c`}: cooked and raw text of each chunk, and the substitutions between them. */
    record TaggedTemplate(Expr tag, List<String> cooked, List<String> raw, List<Expr> exprs, int line) implements Expr {}

    /** A call the parser makes on the program's behalf, into the runtime (the rest of a function's arguments, say). */
    record Internal(String name, List<Expr> args, int line) implements Expr {}

    /**
     * One step of a property or call chain. {@code kind} is 'm' (member: {@code name}), 'i' (index: {@code index})
     * or 'c' (call: {@code args}); {@code optional} is set when the step was written with {@code ?.}.
     */
    record ChainOp(char kind, String name, Expr index, List<Expr> args, boolean optional) {}

    /** A chain containing at least one {@code ?.}: when one of those meets null or undefined the whole chain is undefined. */
    record Chain(Expr base, List<ChainOp> ops, int line) implements Expr {}

    sealed interface Stmt {
        int line();
    }

    record Declarator(String name, Expr init) {}

    record VarDecl(String kind, List<Declarator> declarators, int line) implements Stmt {}

    record ExprStmt(Expr expr, int line) implements Stmt {}

    record If(Expr test, Stmt then, Stmt otherwise, int line) implements Stmt {}

    record While(Expr test, Stmt body, int line) implements Stmt {}

    record DoWhile(Stmt body, Expr test, int line) implements Stmt {}

    /** {@code init} is a VarDecl, an ExprStmt or null. */
    record For(Stmt init, Expr test, Expr update, Stmt body, int line) implements Stmt {}

    /** {@code for (x of iterable)}, or {@code for (x in object)} when {@code in} is set. */
    record ForOf(String kind, String name, Expr iterable, Stmt body, int line, boolean in) implements Stmt {}

    record Block(List<Stmt> body, int line) implements Stmt {}

    record Return(Expr value, int line) implements Stmt {}

    record Throw(Expr value, int line) implements Stmt {}

    /** {@code param} is null for {@code catch {}}; {@code handler} or {@code finalizer} may be null, but not both. */
    record Try(Block block, String param, Block handler, Block finalizer, int line) implements Stmt {}

    /** {@code label} is null for a plain break or continue. */
    record Break(String label, int line) implements Stmt {}

    record Continue(String label, int line) implements Stmt {}

    record Labeled(String label, Stmt body, int line) implements Stmt {}

    /** {@code test} is null for {@code default}. */
    record Case(Expr test, List<Stmt> body) {}

    record Switch(Expr discriminant, List<Case> cases, int line) implements Stmt {}

    record FunctionDecl(Function function, int line) implements Stmt {}

    record Empty(int line) implements Stmt {}

    record ClassDecl(String name, ClassExpr cls, int line) implements Stmt {}

    /** A function body is always a statement list; an arrow with an expression body gets a Return. */
    record Function(String name, List<String> params, List<Stmt> body, boolean arrow, int line) {}
}
