package duke.js;

import static duke.compiler.asm.Reg.*;

import duke.compiler.asm.Cond;
import duke.compiler.asm.Mem;
import duke.compiler.asm.Reg;
import duke.compiler.asm.X64;
import duke.compiler.asm.X64.Alu;
import duke.compiler.asm.X64.Label;
import duke.compiler.asm.X64.Shift;
import duke.compiler.image.Image;
import duke.compiler.image.Reloc;
import duke.compiler.image.Section;
import duke.js.Analyzer.FuncInfo;
import duke.js.Analyzer.Scope;
import duke.js.Analyzer.Var;
import duke.js.Node.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Emits x86-64 for the analyzed program, a template at a time like dukec's {@code MethodCompiler}.
 *
 * <p>Values are 64-bit words. Low bit 1 is an integer (value &lt;&lt; 1 | 1). Low two bits 00 is a
 * pointer to a heap object. Low two bits 10 are the constants undefined, null, false and true.
 * Expression results are in rax; temporaries live on the machine stack.
 *
 * <p>Calling convention: the caller pushes the {@code this} value, the callee closure, then the
 * arguments left to right, and passes the closure in rdi, {@code this} in rsi and the argument
 * count in rdx. The callee finds argument i at {@code [rbp + 16 + 8*(argc-1-i)]}, returns in rax
 * and leaves popping to the caller.
 */
final class CodeGen {

    static final long BASE = 0x400000;
    static final int UNDEF = 2;
    static final int NULL = 6;
    static final int FALSE = 10;
    static final int TRUE = 14;
    /** Closure header: type tag 4 as an integer. */
    private static final int FUNCTION_TAG = 9;
    /** Words before a closure's captured boxes: tag, code address, properties. */
    private static final int CLOSURE_HEADER = 24;
    private static final int HEAP_BYTES = 1 << 30;

    private record Loop(Label breakTo, Label continueTo) {}

    private final Analyzer an;
    private final Image image = new Image();
    private final Section text = image.text;
    private final X64 asm = new X64(text);
    private final Map<String, String> strings = new LinkedHashMap<>();
    private final ArrayDeque<FuncInfo> queue = new ArrayDeque<>();
    private final Set<FuncInfo> queued = new HashSet<>();
    private final ArrayDeque<Loop> loops = new ArrayDeque<>();
    private final Label divideByZero = new Label();
    private final Label notFunction = new Label();
    private final Set<Label> stubsUsed = new HashSet<>();
    private final String file;
    private FuncInfo fn;

    CodeGen(String file, Analyzer an) {
        this.file = file;
        this.an = an;
    }

    Image generate(List<Stmt> prelude, List<Stmt> program) {
        image.bss.align(8);
        image.define("heap.ptr", image.bss, image.bss.size(), 8, Image.SymbolType.OBJECT);
        image.bss.reserve(8);
        image.define("heap.end", image.bss, image.bss.size(), 8, Image.SymbolType.OBJECT);
        image.bss.reserve(8);
        emitStart();
        emitAllocator();

        for (Var v : an.globals) {
            if (v.declared != null) {
                enqueue(an.functions.get(v.declared));
            }
        }
        List<Stmt> all = new ArrayList<>(prelude);
        all.addAll(program);
        compileFunction(an.main(), all);
        while (!queue.isEmpty()) {
            FuncInfo f = queue.poll();
            compileFunction(f, f.node.body());
            if (queue.isEmpty()) {
                emitStubs();
            }
        }
        emitData();
        return image;
    }

    private void enqueue(FuncInfo f) {
        if (queued.add(f)) {
            queue.add(f);
        }
    }

    // ---- process entry, allocator, stubs ----

    private void emitStart() {
        text.align(16);
        image.define("_start", text, text.size(), 0, Image.SymbolType.FUNC);
        Label failed = new Label();
        asm.movImm32(RAX, 9); // mmap
        asm.alu(Alu.XOR, false, RDI, RDI);
        asm.movImm32(RSI, HEAP_BYTES);
        asm.movImm32(RDX, 3); // PROT_READ | PROT_WRITE
        asm.movImm32(R10, 0x4022); // MAP_PRIVATE | MAP_ANONYMOUS | MAP_NORESERVE
        asm.movImm64(R8, -1);
        asm.alu(Alu.XOR, false, R9, R9);
        asm.syscall();
        asm.test(true, RAX, RAX);
        asm.jcc(Cond.S, failed);
        asm.store(8, Mem.rip("heap.ptr"), RAX);
        asm.aluImm(Alu.ADD, true, RAX, HEAP_BYTES);
        asm.store(8, Mem.rip("heap.end"), RAX);
        asm.alu(Alu.XOR, false, RBP, RBP);
        asm.alu(Alu.XOR, false, RDI, RDI);
        asm.movImm32(RSI, UNDEF);
        asm.alu(Alu.XOR, false, RDX, RDX);
        asm.call("fn.main");
        asm.movImm32(RAX, 231); // exit_group
        asm.alu(Alu.XOR, false, RDI, RDI);
        asm.syscall();
        asm.bind(failed);
        asm.movImm32(RAX, 231);
        asm.movImm32(RDI, 4);
        asm.syscall();
    }

    /** {@code rt.alloc}: rcx = bytes, returns rax = zeroed memory, clobbers rdx and r11. */
    private void emitAllocator() {
        String message = "out of memory\n";
        text.align(16);
        int start = text.size();
        Label oom = new Label();
        asm.aluImm(Alu.ADD, true, RCX, 7);
        asm.aluImm(Alu.AND, true, RCX, -8);
        asm.load(8, false, RAX, Mem.rip("heap.ptr"));
        asm.lea(RDX, Mem.at(RAX, RCX, 1, 0));
        asm.load(8, false, R11, Mem.rip("heap.end"));
        asm.alu(Alu.CMP, true, RDX, R11);
        asm.jcc(Cond.A, oom);
        asm.store(8, Mem.rip("heap.ptr"), RDX);
        asm.ret();
        asm.bind(oom);
        asm.movImm32(RAX, 1);
        asm.movImm32(RDI, 2);
        asm.lea(RSI, Mem.rip("oom.message"));
        asm.movImm32(RDX, message.length());
        asm.syscall();
        asm.movImm32(RAX, 231);
        asm.movImm32(RDI, 3);
        asm.syscall();
        image.define("rt.alloc", text, start, text.size() - start, Image.SymbolType.FUNC);
        image.rodata.align(8);
        image.define("oom.message", image.rodata, image.rodata.size(), message.length(), Image.SymbolType.OBJECT);
        image.rodata.emitBytes(message.getBytes(StandardCharsets.US_ASCII));
    }

    /** Out-of-line failure paths shared by every function. They never return. */
    private void emitStubs() {
        if (stubsUsed.remove(divideByZero)) {
            text.align(16);
            asm.bind(divideByZero);
            callRuntime("__divideByZero", 0);
            asm.ud2();
        }
        if (stubsUsed.remove(notFunction)) {
            text.align(16);
            asm.bind(notFunction);
            callRuntime("__notFunction", 0);
            asm.ud2();
        }
    }

    private void jumpToStub(Cond cond, Label stub) {
        stubsUsed.add(stub);
        asm.jcc(cond, stub);
    }

    private void emitData() {
        image.data.align(8);
        for (Var v : an.globals) {
            image.define(v.symbol, image.data, image.data.size(), 8, Image.SymbolType.OBJECT);
            if (v.declared != null) {
                image.data.emitReloc(Reloc.Kind.ABS64, "sc." + an.functions.get(v.declared).symbol, 0);
            } else {
                image.data.emit64(UNDEF);
            }
        }
        // Static closures for top-level function declarations: no captures, so no allocation needed.
        for (Var v : an.globals) {
            if (v.declared != null) {
                FuncInfo f = an.functions.get(v.declared);
                if (!f.captures.isEmpty()) {
                    throw new IllegalStateException("top-level function captures variables: " + v.name);
                }
                image.define("sc." + f.symbol, image.data, image.data.size(), 24, Image.SymbolType.OBJECT);
                image.data.emit64(FUNCTION_TAG);
                image.data.emitReloc(Reloc.Kind.ABS64, f.symbol, 0);
                image.data.emit64(UNDEF);
            }
        }
        for (Map.Entry<String, String> e : strings.entrySet()) {
            byte[] bytes = e.getKey().getBytes(StandardCharsets.US_ASCII);
            image.data.align(8);
            image.define(e.getValue(), image.data, image.data.size(), 16 + bytes.length, Image.SymbolType.OBJECT);
            image.data.emit64(3); // type tag 1 as an integer
            image.data.emit64((long) bytes.length << 1 | 1);
            image.data.emitBytes(bytes);
        }
        image.data.align(8);
    }

    // ---- functions ----

    private static Mem slot(int index) {
        return Mem.at(RBP, -8 * (index + 1));
    }

    private void compileFunction(FuncInfo f, List<Stmt> body) {
        fn = f;
        loops.clear();
        text.align(16);
        int start = text.size();
        asm.push(RBP);
        asm.mov(RBP, RSP);
        asm.aluImm(Alu.SUB, true, RSP, 8 * f.slots);
        asm.store(8, slot(Analyzer.CLOSURE_SLOT), RDI);
        asm.store(8, slot(Analyzer.THIS_SLOT), RSI);
        asm.store(8, slot(Analyzer.ARGC_SLOT), RDX);
        asm.movImm32(R11, UNDEF);
        for (int s = 3; s < f.slots; s++) {
            asm.store(8, slot(s), R11);
        }
        Scope scope = f.main ? null : f.scope;
        if (scope != null) {
            enterScope(scope);
            List<String> params = f.node.params();
            for (Var v : scope.vars.values()) {
                if (v.kind.equals("this")) {
                    asm.load(8, false, RAX, slot(Analyzer.THIS_SLOT));
                    storeVar(v);
                } else if (v.kind.equals("self")) {
                    asm.load(8, false, RAX, slot(Analyzer.CLOSURE_SLOT));
                    storeVar(v);
                }
            }
            // r8 = argc, r9 = base of the argument block, so argument i is at [r9 - 8*(i+1)].
            asm.load(8, false, R8, slot(Analyzer.ARGC_SLOT));
            asm.lea(R9, Mem.at(RBP, R8, 8, 16));
            for (int i = 0; i < params.size(); i++) {
                Label missing = new Label();
                asm.movImm32(RAX, UNDEF);
                asm.aluImm(Alu.CMP, true, R8, i);
                asm.jcc(Cond.LE, missing);
                asm.load(8, false, RAX, Mem.at(R9, -8 * (i + 1)));
                asm.bind(missing);
                storeVar(scope.vars.get(params.get(i)));
            }
        }
        for (Stmt s : body) {
            gen(s);
        }
        asm.movImm32(RAX, UNDEF);
        asm.leave();
        asm.ret();
        image.define(f.symbol, text, start, text.size() - start, Image.SymbolType.FUNC);
    }

    /** Allocates boxes for captured variables, then creates closures for hoisted function declarations. */
    private void enterScope(Scope scope) {
        if (scope.topLevel) {
            return;
        }
        for (Var v : scope.vars.values()) {
            if (v.boxed()) {
                allocBox();
                asm.store(8, slot(v.slot), RAX);
            }
        }
        for (FunctionDecl d : scope.hoisted) {
            makeClosure(an.functions.get(d.function()));
            storeVar(an.resolved.get(d));
        }
    }

    private void allocBox() {
        asm.movImm32(RCX, 8);
        asm.call("rt.alloc");
        asm.movImm32(RDX, UNDEF);
        asm.store(8, Mem.at(RAX), RDX);
    }

    private void makeClosure(FuncInfo f) {
        enqueue(f);
        int n = f.captures.size();
        asm.movImm32(RCX, CLOSURE_HEADER + 8 * n);
        asm.call("rt.alloc");
        asm.movImm32(RDX, FUNCTION_TAG);
        asm.store(8, Mem.at(RAX), RDX);
        asm.lea(RDX, Mem.rip(f.symbol));
        asm.store(8, Mem.at(RAX, 8), RDX);
        asm.movImm32(RDX, UNDEF);
        asm.store(8, Mem.at(RAX, 16), RDX);
        for (int j = 0; j < n; j++) {
            loadBoxPointer(f.captures.get(j), RDX);
            asm.store(8, Mem.at(RAX, CLOSURE_HEADER + 8 * j), RDX);
        }
    }

    /** The box holding {@code v}, as seen from the current function. */
    private void loadBoxPointer(Var v, Reg dst) {
        if (v.owner == fn) {
            asm.load(8, false, dst, slot(v.slot));
        } else {
            asm.load(8, false, dst, slot(Analyzer.CLOSURE_SLOT));
            asm.load(8, false, dst, Mem.at(dst, CLOSURE_HEADER + 8 * fn.captures.indexOf(v)));
        }
    }

    private void loadVar(Var v, Reg dst) {
        if (v.global) {
            asm.load(8, false, dst, Mem.rip(v.symbol));
        } else if (v.owner == fn && !v.captured) {
            asm.load(8, false, dst, slot(v.slot));
        } else {
            loadBoxPointer(v, dst);
            asm.load(8, false, dst, Mem.at(dst));
        }
    }

    /** Stores rax into {@code v}, clobbering rcx. */
    private void storeVar(Var v) {
        if (v.global) {
            asm.store(8, Mem.rip(v.symbol), RAX);
        } else if (v.owner == fn && !v.captured) {
            asm.store(8, slot(v.slot), RAX);
        } else {
            loadBoxPointer(v, RCX);
            asm.store(8, Mem.at(RCX), RAX);
        }
    }

    // ---- statements ----

    private void gen(Stmt s) {
        switch (s) {
            case VarDecl d -> {
                for (Declarator decl : d.declarators()) {
                    Var v = an.resolved.get(decl);
                    if (decl.init() != null) {
                        gen(decl.init());
                    } else if (d.kind().equals("var")) {
                        continue;
                    } else {
                        asm.movImm32(RAX, UNDEF);
                    }
                    storeVar(v);
                }
            }
            case ExprStmt e -> gen(e.expr());
            case If i -> {
                Label otherwise = new Label();
                Label end = new Label();
                gen(i.test());
                truthyJump(otherwise);
                gen(i.then());
                if (i.otherwise() != null) {
                    asm.jmp(end);
                }
                asm.bind(otherwise);
                if (i.otherwise() != null) {
                    gen(i.otherwise());
                }
                asm.bind(end);
            }
            case While w -> {
                Label top = new Label();
                Label end = new Label();
                asm.bind(top);
                gen(w.test());
                truthyJump(end);
                loops.push(new Loop(end, top));
                gen(w.body());
                loops.pop();
                asm.jmp(top);
                asm.bind(end);
            }
            case DoWhile w -> {
                Label top = new Label();
                Label cont = new Label();
                Label end = new Label();
                asm.bind(top);
                loops.push(new Loop(end, cont));
                gen(w.body());
                loops.pop();
                asm.bind(cont);
                gen(w.test());
                truthyJump(end);
                asm.jmp(top);
                asm.bind(end);
            }
            case For f -> genFor(f);
            case ForOf f -> genForOf(f);
            case Block b -> {
                enterScope(an.scopes.get(b));
                for (Stmt inner : b.body()) {
                    gen(inner);
                }
            }
            case Return r -> {
                if (r.value() != null) {
                    gen(r.value());
                } else {
                    asm.movImm32(RAX, UNDEF);
                }
                asm.leave();
                asm.ret();
            }
            case Switch sw -> throw error(sw.line(), "'switch' is not supported by the x86 back end");
            case Labeled l -> throw error(l.line(), "labels are not supported by the x86 back end");
            case Throw t -> throw error(t.line(), "'throw' is not supported by the x86 back end");
            case Try t -> throw error(t.line(), "'try' is not supported by the x86 back end");
            case Break b -> {
                if (b.label() != null) {
                    throw error(b.line(), "labels are not supported by the x86 back end");
                }
                if (loops.isEmpty()) {
                    throw error(b.line(), "'break' outside a loop");
                }
                asm.jmp(loops.peek().breakTo());
            }
            case Continue c -> {
                if (c.label() != null) {
                    throw error(c.line(), "labels are not supported by the x86 back end");
                }
                if (loops.isEmpty()) {
                    throw error(c.line(), "'continue' outside a loop");
                }
                asm.jmp(loops.peek().continueTo());
            }
            case FunctionDecl d -> { }
            case Empty e -> { }
        }
    }

    private void genFor(For f) {
        Scope scope = an.scopes.get(f);
        Label top = new Label();
        Label cont = new Label();
        Label end = new Label();
        enterScope(scope);
        if (f.init() != null) {
            gen(f.init());
        }
        asm.bind(top);
        if (f.test() != null) {
            gen(f.test());
            truthyJump(end);
        }
        loops.push(new Loop(end, cont));
        gen(f.body());
        loops.pop();
        asm.bind(cont);
        // Each iteration gets its own copy of a captured loop variable, as in JavaScript.
        for (Var v : scope.vars.values()) {
            if (v.boxed()) {
                loadVar(v, RAX);
                asm.push(RAX);
                allocBox();
                asm.pop(RCX);
                asm.store(8, Mem.at(RAX), RCX);
                asm.store(8, slot(v.slot), RAX);
            }
        }
        if (f.update() != null) {
            gen(f.update());
        }
        asm.jmp(top);
        asm.bind(end);
    }

    private void genForOf(ForOf f) {
        if (f.in()) {
            throw error(f.line(), "'for...in' is not supported by the x86 back end");
        }
        int[] hidden = an.iterationSlots.get(f);
        Scope scope = an.scopes.get(f);
        Var v = an.resolved.get(f);
        Label top = new Label();
        Label cont = new Label();
        Label end = new Label();
        gen(f.iterable());
        asm.store(8, slot(hidden[0]), RAX);
        asm.movImm32(RAX, 1);
        asm.store(8, slot(hidden[1]), RAX);
        asm.bind(top);
        asm.push(slot(hidden[0]));
        callRuntime("__length", 1);
        asm.load(8, false, RCX, slot(hidden[1]));
        asm.alu(Alu.CMP, true, RCX, RAX);
        asm.jcc(Cond.GE, end);
        enterScope(scope);
        asm.push(slot(hidden[0]));
        asm.push(slot(hidden[1]));
        callRuntime("__get", 2);
        storeVar(v);
        loops.push(new Loop(end, cont));
        gen(f.body());
        loops.pop();
        asm.bind(cont);
        asm.load(8, false, RAX, slot(hidden[1]));
        asm.aluImm(Alu.ADD, true, RAX, 2);
        asm.store(8, slot(hidden[1]), RAX);
        asm.jmp(top);
        asm.bind(end);
    }

    // ---- truthiness ----

    /**
     * Jumps to {@code falsy} when rax is falsy, else falls through. rax keeps its value on both
     * paths, which {@code &&}, {@code ||} and {@code ?:} rely on.
     */
    private void truthyJump(Label falsy) {
        Label truthy = new Label();
        asm.aluImm(Alu.CMP, true, RAX, TRUE);
        asm.jcc(Cond.E, truthy);
        asm.aluImm(Alu.CMP, true, RAX, FALSE);
        asm.jcc(Cond.E, falsy);
        asm.push(RAX);
        asm.push(RAX);
        callRuntime("__truthy", 1);
        asm.pop(RCX);
        asm.aluImm(Alu.CMP, true, RAX, TRUE);
        asm.mov(RAX, RCX);
        asm.jcc(Cond.NE, falsy);
        asm.bind(truthy);
    }

    // ---- expressions ----

    private void gen(Expr e) {
        switch (e) {
            case Num n -> asm.movImm64(RAX, n.value() << 1 | 1);
            case Str s -> loadString(s.value());
            case Lit l -> asm.movImm32(RAX, switch (l.value()) {
                case TRUE -> CodeGen.TRUE;
                case FALSE -> CodeGen.FALSE;
                case NULL -> CodeGen.NULL;
                case UNDEFINED -> UNDEF;
            });
            case This t -> {
                Var v = an.resolved.get(t);
                if (v == null) {
                    asm.movImm32(RAX, UNDEF);
                } else {
                    loadVar(v, RAX);
                }
            }
            case Ident id -> {
                Var v = an.resolved.get(id);
                if (v == null) {
                    throw error(id.line(), id.name() + " is a compiler intrinsic and can only be called");
                }
                loadVar(v, RAX);
            }
            case Template t -> genTemplate(t);
            case ArrayLit a -> {
                callRuntime("__newArray", 0);
                asm.push(RAX);
                for (Expr element : a.elements()) {
                    gen(element);
                    asm.push(Mem.at(RSP));
                    asm.push(RAX);
                    callRuntime("__push", 2);
                }
                asm.pop(RAX);
            }
            case ObjectLit o -> {
                callRuntime("__newObject", 0);
                asm.push(RAX);
                for (Property p : o.properties()) {
                    asm.push(Mem.at(RSP));
                    loadString(p.key());
                    asm.push(RAX);
                    gen(p.value());
                    asm.push(RAX);
                    callRuntime("__set", 3);
                }
                asm.pop(RAX);
            }
            case FuncExpr f -> makeClosure(an.functions.get(f.function()));
            case Unary u -> genUnary(u);
            case Update u -> genUpdate(u);
            case Binary b -> {
                gen(b.left());
                asm.push(RAX);
                gen(b.right());
                binary(b.op(), b.line());
            }
            case Logical l -> genLogical(l);
            case Assign a -> genAssign(a);
            case Conditional c -> {
                Label otherwise = new Label();
                Label end = new Label();
                gen(c.test());
                truthyJump(otherwise);
                gen(c.then());
                asm.jmp(end);
                asm.bind(otherwise);
                gen(c.otherwise());
                asm.bind(end);
            }
            case Call c -> genCall(c);
            case New n -> throw error(n.line(), "'new' is not supported by the x86 back end");
            case Chain c -> throw error(c.line(), "optional chaining is not supported by the x86 back end");
            case Member m -> {
                gen(m.object());
                asm.push(RAX);
                loadString(m.name());
                asm.push(RAX);
                callRuntime("__get", 2);
            }
            case Index i -> {
                gen(i.object());
                asm.push(RAX);
                gen(i.index());
                asm.push(RAX);
                callRuntime("__get", 2);
            }
            case Sequence s -> s.exprs().forEach(this::gen);
        }
    }

    private void loadString(String value) {
        String symbol = strings.computeIfAbsent(value, v -> "s." + strings.size());
        asm.lea(RAX, Mem.rip(symbol));
    }

    private void genTemplate(Template t) {
        loadString(t.chunks().get(0));
        for (int i = 0; i < t.exprs().size(); i++) {
            asm.push(RAX);
            gen(t.exprs().get(i));
            binary("+", t.line());
            String chunk = t.chunks().get(i + 1);
            if (!chunk.isEmpty()) {
                asm.push(RAX);
                loadString(chunk);
                binary("+", t.line());
            }
        }
    }

    private void genLogical(Logical l) {
        Label end = new Label();
        gen(l.left());
        switch (l.op()) {
            case "&&" -> truthyJump(end);
            case "||" -> {
                Label evaluateRight = new Label();
                truthyJump(evaluateRight);
                asm.jmp(end);
                asm.bind(evaluateRight);
            }
            default -> { // ??
                Label evaluateRight = new Label();
                asm.aluImm(Alu.CMP, true, RAX, UNDEF);
                asm.jcc(Cond.E, evaluateRight);
                asm.aluImm(Alu.CMP, true, RAX, NULL);
                asm.jcc(Cond.NE, end);
                asm.bind(evaluateRight);
            }
        }
        gen(l.right());
        asm.bind(end);
    }

    private void genUnary(Unary u) {
        gen(u.operand());
        switch (u.op()) {
            case "!" -> {
                Label falsy = new Label();
                Label end = new Label();
                truthyJump(falsy);
                asm.movImm32(RAX, FALSE);
                asm.jmp(end);
                asm.bind(falsy);
                asm.movImm32(RAX, TRUE);
                asm.bind(end);
            }
            case "-" -> {
                Label slow = new Label();
                Label end = new Label();
                asm.mov(RCX, RAX);
                asm.aluImm(Alu.AND, false, RCX, 1);
                asm.jcc(Cond.E, slow);
                // -x is 2 - tagged(x).
                asm.mov(RCX, RAX);
                asm.movImm32(RAX, 2);
                asm.alu(Alu.SUB, true, RAX, RCX);
                asm.jmp(end);
                asm.bind(slow);
                asm.push(RAX);
                callRuntime("__negate", 1);
                asm.bind(end);
            }
            case "+" -> {
                asm.push(RAX);
                callRuntime("__toNumber", 1);
            }
            case "~" -> {
                asm.shiftImm(Shift.SAR, true, RAX, 1);
                asm.not(false, RAX);
                retag32();
            }
            case "typeof" -> {
                asm.push(RAX);
                callRuntime("__typeof", 1);
            }
            default -> throw error(u.line(), "unsupported operator " + u.op());
        }
    }

    /** Sign-extends the low 32 bits of the untagged value in rax and tags it. */
    private void retag32() {
        asm.movsxd(RAX, RAX);
        retag();
    }

    private void retag() {
        asm.lea(RAX, Mem.at(RAX, RAX, 1, 1));
    }

    private void genUpdate(Update u) {
        String op = u.op().equals("++") ? "+" : "-";
        switch (u.target()) {
            case Ident id -> {
                Var v = assignable(id);
                loadVar(v, RAX);
                if (!u.prefix()) {
                    asm.push(RAX);
                }
                asm.push(RAX);
                asm.movImm32(RAX, 3);
                binary(op, u.line());
                storeVar(v);
                if (!u.prefix()) {
                    asm.pop(RAX);
                }
            }
            default -> genReferenceUpdate(u.target(), op, null, !u.prefix(), u.line());
        }
    }

    /**
     * Read-modify-write of {@code o[k]}, evaluating o and k once. The new value is computed by
     * {@code value} (or the constant 1 when null) and combined with {@code op}. The result is the
     * old value when {@code postfix}, else the new one.
     */
    private void genReferenceUpdate(Expr target, String op, Expr value, boolean postfix, int line) {
        gen(referenceObject(target));
        asm.push(RAX);
        pushKey(target);
        asm.push(Mem.at(RSP, 8));
        asm.push(Mem.at(RSP, 8));
        callRuntime("__get", 2);
        if (postfix) {
            asm.push(RAX);
        }
        asm.push(RAX);
        if (value == null) {
            asm.movImm32(RAX, 3);
        } else {
            gen(value);
        }
        binary(op, line);
        int extra = postfix ? 1 : 0;
        asm.push(Mem.at(RSP, 8 * (extra + 1)));
        asm.push(Mem.at(RSP, 8 * (extra + 1)));
        asm.push(RAX);
        callRuntime("__set", 3);
        if (postfix) {
            asm.pop(RAX);
        }
        asm.aluImm(Alu.ADD, true, RSP, 16);
    }

    private Expr referenceObject(Expr target) {
        return target instanceof Member m ? m.object() : ((Index) target).object();
    }

    /** Pushes the property key of a Member or Index target. */
    private void pushKey(Expr target) {
        if (target instanceof Member m) {
            loadString(m.name());
        } else {
            gen(((Index) target).index());
        }
        asm.push(RAX);
    }

    private Var assignable(Ident id) {
        Var v = an.resolved.get(id);
        if (v == null) {
            throw error(id.line(), "cannot assign to " + id.name());
        }
        if (v.kind.equals("const")) {
            throw error(id.line(), "Assignment to constant variable '" + id.name() + "'");
        }
        return v;
    }

    private void genAssign(Assign a) {
        String op = a.op();
        switch (a.target()) {
            case Ident id -> {
                Var v = assignable(id);
                if (op.equals("=")) {
                    gen(a.value());
                } else if (op.equals("&&=") || op.equals("||=") || op.equals("??=")) {
                    genLogical(new Logical(op.substring(0, op.length() - 1), id, a.value(), a.line()));
                } else {
                    loadVar(v, RAX);
                    asm.push(RAX);
                    gen(a.value());
                    binary(op.substring(0, op.length() - 1), a.line());
                }
                storeVar(v);
            }
            case Member m -> assignReference(a, m);
            case Index i -> assignReference(a, i);
            default -> throw error(a.line(), "invalid assignment target");
        }
    }

    private void assignReference(Assign a, Expr target) {
        String op = a.op();
        if (op.equals("=")) {
            gen(referenceObject(target));
            asm.push(RAX);
            pushKey(target);
            gen(a.value());
            asm.push(RAX);
            callRuntime("__set", 3);
        } else if (op.equals("&&=") || op.equals("||=") || op.equals("??=")) {
            throw error(a.line(), "logical assignment is only supported on plain variables");
        } else {
            genReferenceUpdate(target, op.substring(0, op.length() - 1), a.value(), false, a.line());
        }
    }

    // ---- calls ----

    private void genCall(Call c) {
        if (c.callee() instanceof Ident id && an.resolved.get(id) == null) {
            genIntrinsic(id, c);
            return;
        }
        // Stack, deepest first: this, callee, args...
        switch (c.callee()) {
            case Member m -> {
                gen(m.object());
                asm.push(RAX);
                asm.push(RAX);
                loadString(m.name());
                asm.push(RAX);
                callRuntime("__get", 2);
                asm.push(RAX);
            }
            case Index i -> {
                gen(i.object());
                asm.push(RAX);
                asm.push(RAX);
                gen(i.index());
                asm.push(RAX);
                callRuntime("__get", 2);
                asm.push(RAX);
            }
            default -> {
                asm.pushImm(UNDEF);
                gen(c.callee());
                asm.push(RAX);
            }
        }
        for (Expr arg : c.args()) {
            gen(arg);
            asm.push(RAX);
        }
        int argc = c.args().size();
        asm.load(8, false, RDI, Mem.at(RSP, 8 * argc));
        asm.load(8, false, RSI, Mem.at(RSP, 8 * (argc + 1)));
        asm.mov(RAX, RDI);
        asm.aluImm(Alu.AND, false, RAX, 3);
        jumpToStub(Cond.NE, notFunction);
        asm.load(8, false, RAX, Mem.at(RDI));
        asm.aluImm(Alu.CMP, true, RAX, FUNCTION_TAG);
        jumpToStub(Cond.NE, notFunction);
        asm.movImm32(RDX, argc);
        asm.call(Mem.at(RDI, 8));
        asm.aluImm(Alu.ADD, true, RSP, 8 * (argc + 2));
    }

    /** Calls a prelude function directly with {@code argc} arguments already pushed. */
    private void callRuntime(String name, int argc) {
        Var v = an.preludeScope().vars.get(name);
        if (v == null || v.declared == null) {
            throw new IllegalStateException("the prelude does not define " + name);
        }
        FuncInfo target = an.functions.get(v.declared);
        enqueue(target);
        asm.movImm32(RDX, argc);
        asm.alu(Alu.XOR, false, RDI, RDI);
        asm.movImm32(RSI, UNDEF);
        asm.call(target.symbol);
        if (argc > 0) {
            asm.aluImm(Alu.ADD, true, RSP, 8 * argc);
        }
    }

    private static final Reg[] SYSCALL_REGS = {RAX, RDI, RSI, RDX, R10, R8, R9};

    private void genIntrinsic(Ident id, Call c) {
        List<Expr> args = c.args();
        String name = id.name();
        int expected = switch (name) {
            case "__argc" -> 0;
            case "__peek", "__peekByte", "__addr", "__ptr", "__alloc", "__arg", "__isInt", "__isPtr" -> 1;
            case "__poke", "__pokeByte", "__identical" -> 2;
            case "__copy" -> 3;
            default -> -1; // __syscall takes a number and up to six arguments
        };
        if (expected >= 0 && args.size() != expected || expected < 0 && (args.isEmpty() || args.size() > 7)) {
            throw error(c.line(), name + " called with the wrong number of arguments");
        }
        for (Expr arg : args) {
            gen(arg);
            asm.push(RAX);
        }
        switch (name) {
            case "__argc" -> {
                asm.load(8, false, RAX, slot(Analyzer.ARGC_SLOT));
                retag();
            }
            case "__arg" -> {
                Label done = new Label();
                asm.pop(RDX);
                asm.shiftImm(Shift.SAR, true, RDX, 1);
                asm.load(8, false, RCX, slot(Analyzer.ARGC_SLOT));
                asm.movImm32(RAX, UNDEF);
                asm.alu(Alu.CMP, true, RDX, RCX);
                asm.jcc(Cond.GE, done);
                asm.alu(Alu.SUB, true, RCX, RDX);
                asm.load(8, false, RAX, Mem.at(RBP, RCX, 8, 8));
                asm.bind(done);
            }
            case "__peek" -> {
                asm.pop(RAX);
                asm.shiftImm(Shift.SAR, true, RAX, 1);
                asm.load(8, false, RAX, Mem.at(RAX));
            }
            case "__peekByte" -> {
                asm.pop(RAX);
                asm.shiftImm(Shift.SAR, true, RAX, 1);
                asm.load(1, false, RAX, Mem.at(RAX));
                retag();
            }
            case "__poke" -> {
                asm.pop(RDX);
                asm.pop(RCX);
                asm.shiftImm(Shift.SAR, true, RCX, 1);
                asm.store(8, Mem.at(RCX), RDX);
                asm.movImm32(RAX, UNDEF);
            }
            case "__pokeByte" -> {
                asm.pop(RAX);
                asm.pop(RCX);
                asm.shiftImm(Shift.SAR, true, RAX, 1);
                asm.shiftImm(Shift.SAR, true, RCX, 1);
                asm.store(1, Mem.at(RCX), RAX);
                asm.movImm32(RAX, UNDEF);
            }
            case "__addr" -> {
                asm.pop(RAX);
                retag();
            }
            case "__ptr" -> {
                asm.pop(RAX);
                asm.shiftImm(Shift.SAR, true, RAX, 1);
            }
            case "__alloc" -> {
                asm.pop(RCX);
                asm.shiftImm(Shift.SAR, true, RCX, 1);
                asm.call("rt.alloc");
                retag();
            }
            case "__copy" -> {
                asm.pop(RCX);
                asm.pop(RSI);
                asm.pop(RDI);
                for (Reg r : new Reg[] {RCX, RSI, RDI}) {
                    asm.shiftImm(Shift.SAR, true, r, 1);
                }
                asm.repMovsb();
                asm.movImm32(RAX, UNDEF);
            }
            case "__isInt" -> {
                asm.pop(RAX);
                asm.aluImm(Alu.AND, false, RAX, 1);
                boolFromBit();
            }
            case "__isPtr" -> {
                asm.pop(RAX);
                asm.aluImm(Alu.AND, false, RAX, 3);
                boolFromFlags(Cond.E);
            }
            case "__identical" -> {
                asm.pop(RAX);
                asm.pop(RCX);
                asm.alu(Alu.CMP, true, RCX, RAX);
                boolFromFlags(Cond.E);
            }
            default -> { // __syscall
                for (int k = args.size() - 1; k >= 0; k--) {
                    asm.pop(SYSCALL_REGS[k]);
                    asm.shiftImm(Shift.SAR, true, SYSCALL_REGS[k], 1);
                }
                asm.syscall();
                retag();
            }
        }
    }

    // ---- operators ----

    private void boolFromBit() {
        asm.shiftImm(Shift.SHL, true, RAX, 2);
        asm.aluImm(Alu.ADD, true, RAX, FALSE);
    }

    private void boolFromFlags(Cond cond) {
        asm.setcc(cond, RAX);
        asm.movzx8(RAX, RAX);
        boolFromBit();
    }

    /** Left operand on the stack, right in rax. Pops the left and leaves the result in rax. */
    private void binary(String op, int line) {
        switch (op) {
            case "===", "!==" -> strictEquals(op.equals("==="));
            case "==", "!=" -> {
                asm.push(RAX);
                callRuntime("__looseEquals", 2);
                if (op.equals("!=")) {
                    asm.aluImm(Alu.XOR, true, RAX, 4);
                }
            }
            case "**" -> {
                asm.push(RAX);
                callRuntime("__pow", 2);
            }
            case "+", "-", "*", "/", "%", "&", "|", "^", "<<", ">>", ">>>" -> arithmetic(op);
            case "<", ">", "<=", ">=" -> comparison(op);
            default -> throw error(line, "unsupported operator " + op);
        }
    }

    private void strictEquals(boolean equal) {
        Label same = new Label();
        Label different = new Label();
        Label end = new Label();
        asm.pop(RCX);
        asm.alu(Alu.CMP, true, RCX, RAX);
        asm.jcc(Cond.E, same);
        asm.mov(RDX, RCX);
        asm.alu(Alu.OR, true, RDX, RAX);
        asm.aluImm(Alu.AND, false, RDX, 3);
        asm.jcc(Cond.NE, different);
        // Two distinct heap pointers: equal only if both are strings with the same bytes.
        asm.push(RCX);
        asm.push(RAX);
        callRuntime("__strictEqualsSlow", 2);
        if (!equal) {
            asm.aluImm(Alu.XOR, true, RAX, 4);
        }
        asm.jmp(end);
        asm.bind(same);
        asm.movImm32(RAX, equal ? TRUE : FALSE);
        asm.jmp(end);
        asm.bind(different);
        asm.movImm32(RAX, equal ? FALSE : TRUE);
        asm.bind(end);
    }

    private void arithmetic(String op) {
        Label slow = new Label();
        Label done = new Label();
        asm.pop(RCX);
        asm.mov(RDX, RCX);
        asm.alu(Alu.AND, true, RDX, RAX);
        asm.aluImm(Alu.AND, false, RDX, 1);
        asm.jcc(Cond.E, slow);
        switch (op) {
            case "+" -> asm.lea(RAX, Mem.at(RCX, RAX, 1, -1));
            case "-" -> {
                asm.alu(Alu.SUB, true, RCX, RAX);
                asm.lea(RAX, Mem.at(RCX, 1));
            }
            case "*" -> {
                asm.shiftImm(Shift.SAR, true, RCX, 1);
                asm.lea(RDX, Mem.at(RAX, -1));
                asm.imul(true, RCX, RDX);
                asm.lea(RAX, Mem.at(RCX, 1));
            }
            case "/", "%" -> {
                asm.mov(R8, RAX);
                asm.shiftImm(Shift.SAR, true, R8, 1);
                asm.test(true, R8, R8);
                jumpToStub(Cond.E, divideByZero);
                asm.shiftImm(Shift.SAR, true, RCX, 1);
                asm.mov(RAX, RCX);
                asm.cqo();
                asm.idiv(true, R8);
                if (op.equals("%")) {
                    asm.mov(RAX, RDX);
                }
                retag();
            }
            case "&" -> {
                asm.alu(Alu.AND, true, RAX, RCX);
                asm.shiftImm(Shift.SAR, true, RAX, 1);
                retag32();
            }
            case "|" -> {
                asm.alu(Alu.OR, true, RAX, RCX);
                asm.shiftImm(Shift.SAR, true, RAX, 1);
                retag32();
            }
            case "^" -> {
                asm.alu(Alu.XOR, true, RAX, RCX);
                asm.shiftImm(Shift.SAR, true, RAX, 1);
                retag32();
            }
            default -> { // shifts: count in cl, value in rax
                asm.mov(R8, RCX);
                asm.mov(RCX, RAX);
                asm.shiftImm(Shift.SAR, true, RCX, 1);
                asm.mov(RAX, R8);
                asm.shiftImm(Shift.SAR, true, RAX, 1);
                switch (op) {
                    case "<<" -> {
                        asm.shiftCl(Shift.SHL, false, RAX);
                        retag32();
                    }
                    case ">>" -> {
                        asm.shiftCl(Shift.SAR, false, RAX);
                        retag32();
                    }
                    default -> {
                        asm.shiftCl(Shift.SHR, false, RAX);
                        retag();
                    }
                }
            }
        }
        asm.jmp(done);
        asm.bind(slow);
        if (op.equals("+")) {
            asm.push(RCX);
            asm.push(RAX);
            callRuntime("__add", 2);
        } else {
            asm.mov(RDX, RAX);
            loadString(op);
            asm.push(RAX);
            asm.push(RCX);
            asm.push(RDX);
            callRuntime("__binary", 3);
        }
        asm.bind(done);
    }

    private void comparison(String op) {
        Label slow = new Label();
        Label join = new Label();
        asm.pop(RCX);
        asm.mov(RDX, RCX);
        asm.alu(Alu.AND, true, RDX, RAX);
        asm.aluImm(Alu.AND, false, RDX, 1);
        asm.jcc(Cond.E, slow);
        asm.alu(Alu.CMP, true, RCX, RAX);
        asm.jmp(join);
        asm.bind(slow);
        asm.push(RCX);
        asm.push(RAX);
        callRuntime("__compare", 2);
        asm.mov(RCX, RAX);
        asm.movImm32(RAX, 1);
        asm.alu(Alu.CMP, true, RCX, RAX);
        asm.bind(join);
        boolFromFlags(switch (op) {
            case "<" -> Cond.L;
            case ">" -> Cond.G;
            case "<=" -> Cond.LE;
            default -> Cond.GE;
        });
    }

    private JsException error(int line, String message) {
        return new JsException(file, line, message);
    }
}
