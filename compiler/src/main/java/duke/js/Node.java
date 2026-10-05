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

    record Property(String key, Expr value) {}

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

    record Member(Expr object, String name, int line) implements Expr {}

    record Index(Expr object, Expr index, int line) implements Expr {}

    record Sequence(List<Expr> exprs, int line) implements Expr {}

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

    record ForOf(String kind, String name, Expr iterable, Stmt body, int line) implements Stmt {}

    record Block(List<Stmt> body, int line) implements Stmt {}

    record Return(Expr value, int line) implements Stmt {}

    record Break(int line) implements Stmt {}

    record Continue(int line) implements Stmt {}

    record FunctionDecl(Function function, int line) implements Stmt {}

    record Empty(int line) implements Stmt {}

    /** A function body is always a statement list; an arrow with an expression body gets a Return. */
    record Function(String name, List<String> params, List<Stmt> body, boolean arrow, int line) {}
}
