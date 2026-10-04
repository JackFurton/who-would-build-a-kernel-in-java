package duke.compiler;

import duke.compiler.asm.Cond;
import duke.compiler.asm.Mem;
import duke.compiler.asm.Reg;
import duke.compiler.asm.X64;
import duke.compiler.image.Image;
import duke.compiler.image.Reloc;
import duke.compiler.image.Section;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassModel;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.attribute.ConstantValueAttribute;
import java.lang.constant.ConstantDesc;
import java.lang.reflect.AccessFlag;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Whole-program compilation: starting from the entry point, compiles every reachable method into
 * one image, then emits class data, string literals, the boot stub and Limine requests.
 */
public final class Compiler {

    public static final String ENTRY_SYMBOL = "_start";
    public static final long KERNEL_BASE = 0xffff_ffff_8000_0000L;

    static final String STRING_CLASS = "java/lang/String";
    static final String CLASS_CLASS = "java/lang/Class";
    static final String BYTE_ARRAY = "[B";

    static final String INTERRUPTS = "duke/kernel/x86/Interrupts";
    static final String INTERRUPT_STUBS = "interrupt.stubs";
    /** Vectors where the CPU pushes an error code; every other stub pushes a 0 in its place. */
    private static final Set<Integer> ERROR_CODE_VECTORS = Set.of(8, 10, 11, 12, 13, 14, 17, 21, 29, 30);
    private static final Reg[] SAVED_REGISTERS = {
            Reg.RAX, Reg.RCX, Reg.RDX, Reg.RBX, Reg.RBP, Reg.RSI, Reg.RDI,
            Reg.R8, Reg.R9, Reg.R10, Reg.R11, Reg.R12, Reg.R13, Reg.R14, Reg.R15};

    static final String STACK_LIMIT = "stack.limit";
    static final String STACK_OVERFLOW = "stack.overflow";
    /** Below the normal limit: room for constructing and throwing StackOverflowError. */
    static final int STACK_RESERVE = 16 * 1024;
    /** Below the emergency limit there's only room to panic. */
    static final int STACK_EMERGENCY = 4 * 1024;

    static final String HEAP_ARENA = "heap.arena";
    static final String METHOD_TABLE = "method.table";
    static final int HEAP_ARENA_SIZE = 16 * 1024 * 1024;

    private static final int BOOT_STACK_SIZE = 64 * 1024;
    private static final int LIMINE_BASE_REVISION = 6;

    private final ClassPool pool;
    private final Layouts layouts;
    private final Image image = new Image();

    private final Deque<ClassPool.ResolvedMethod> worklist = new ArrayDeque<>();
    private final Set<String> queuedMethods = new LinkedHashSet<>();
    /** Reachable classes, every class after its superclass. */
    private final Set<String> classes = new LinkedHashSet<>();
    /** Classes with an initializer stub, in the order something first required one. */
    private final Set<String> initializers = new LinkedHashSet<>();
    private final Map<String, String> strings = new LinkedHashMap<>();
    private final Set<String> tibs = new LinkedHashSet<>();
    private final Set<VirtualCall> virtualCalls = new LinkedHashSet<>();
    private final Set<VirtualCall> interfaceCalls = new LinkedHashSet<>();
    /** Interface method selectors in itable order. */
    private final Map<Vtables.Key, Integer> selectors = new LinkedHashMap<>();
    private final Vtables vtables;
    private final LambdaCompiler lambdas;
    private int multiArraySites;
    private boolean interruptStubs;
    private boolean stackOverflowStub;
    private final Map<String, LineInfo> lineInfo = new LinkedHashMap<>();

    private record LineInfo(String sourceFile, List<int[]> lines) {}

    /** One exception table row; offsets from the method start, catchTib null for catch-all. */
    record Handler(int start, int end, int handler, String catchTib) {}

    private record ExceptionTable(int frameBytes, List<Handler> handlers) {}

    private final Map<String, ExceptionTable> exceptionTables = new LinkedHashMap<>();

    public Compiler(ClassPool pool) {
        this.pool = pool;
        this.layouts = new Layouts(pool);
        this.vtables = new Vtables(pool);
        this.lambdas = new LambdaCompiler(pool);
    }

    private record VirtualCall(String owner, String name, String descriptor) {}

    public Image compile(String entryClass, String entryMethod) {
        ClassPool.ResolvedMethod main = pool.resolveMethod(entryClass, entryMethod, "()V");
        if (!main.is(AccessFlag.STATIC)) {
            throw new CompileException("entry point " + entryClass + "." + entryMethod + " must be static");
        }
        // Every TIB is a Class object carrying its name as a String, so these are always in the image.
        requireClass(CLASS_CLASS);
        requireClass(STRING_CLASS);
        tibs.add(BYTE_ARRAY);
        requireClass(entryClass);
        if (needsInit(entryClass)) {
            requireInitializer(entryClass);
        }
        String mainSymbol = requireMethod(main);

        do {
            while (!worklist.isEmpty()) {
                new MethodCompiler(this, worklist.removeFirst()).compile();
            }
            resolveDispatchTargets();
        } while (!worklist.isEmpty());

        closeTibs();
        // All code first: the method table describes every function in .text.
        emitInitializers();
        if (interruptStubs) {
            emitInterruptStubs();
        }
        if (stackOverflowStub) {
            emitStackOverflowStub();
        }
        emitBootStub(entryClass, mainSymbol);
        emitMethodTable();
        emitStatics();
        emitTibs();
        emitStrings();
        emitHeapArena();
        emitLimineRequests();
        return image;
    }

    ClassPool pool() {
        return pool;
    }

    Layouts layouts() {
        return layouts;
    }

    Image image() {
        return image;
    }

    Vtables vtables() {
        return vtables;
    }

    LambdaCompiler lambdas() {
        return lambdas;
    }

    static String methodSymbol(String owner, String name, String descriptor) {
        return owner + "." + name + descriptor;
    }

    static String staticSymbol(String owner, String name) {
        return owner + "::" + name;
    }

    static String tibSymbol(String type) {
        return "tib:" + type;
    }

    private static String interfacesSymbol(String type) {
        return "interfaces:" + type;
    }

    private static String itableSymbol(String type) {
        return "itable:" + type;
    }

    /** Marks a class reachable: its TIB and statics go into the image. */
    void requireClass(String name) {
        if (classes.contains(name)) {
            return;
        }
        ClassModel model = pool.get(name);
        String superName = pool.superName(model);
        if (superName != null) {
            requireClass(superName);
        }
        classes.add(name);
        tibs.add(name);
        // Intern String constants now so String itself is reachable before the worklist drains.
        for (FieldModel f : model.fields()) {
            if (f.flags().has(AccessFlag.STATIC) && constantValue(f) instanceof String s) {
                requireString(s);
            }
        }
    }

    /** True if initializing {@code type} runs any code: it or a superclass has a static initializer. */
    boolean needsInit(String type) {
        for (String c = type; c != null; ) {
            ClassModel model = pool.get(c);
            if (pool.declaredMethod(model, "<clinit>", "()V") != null) {
                return true;
            }
            if (model.flags().has(AccessFlag.INTERFACE)) {
                return false;
            }
            c = pool.superName(model);
        }
        return false;
    }

    /**
     * The stub that initializes {@code type} on first use (JVMS 5.5): superclass first, then its
     * {@code <clinit>}. The flag is set before running, so re-entry from the same initializer sees
     * the class as initialized, as the JVM's same-thread rule specifies.
     */
    String requireInitializer(String type) {
        requireClass(type);
        if (initializers.add(type)) {
            ClassModel model = pool.get(type);
            String superName = pool.superName(model);
            if (superName != null && !model.flags().has(AccessFlag.INTERFACE) && needsInit(superName)) {
                requireInitializer(superName);
            }
            MethodModel clinit = pool.declaredMethod(model, "<clinit>", "()V");
            if (clinit != null) {
                requireMethod(new ClassPool.ResolvedMethod(model, clinit));
            }
        }
        return initializerSymbol(type);
    }

    static String initializerSymbol(String type) {
        return "initialize:" + type;
    }

    private void emitInitializers() {
        for (String type : initializers) {
            image.bss.align(1);
            int flag = image.bss.size();
            image.bss.reserve(1);
            String flagSymbol = "initialized:" + type;
            image.define(flagSymbol, image.bss, flag, 1, Image.SymbolType.OBJECT);

            Section text = image.text;
            text.align(16);
            int start = text.size();
            X64 a = new X64(text);
            X64.Label done = new X64.Label();
            a.cmpByte(Mem.rip(flagSymbol), 0);
            a.jcc(Cond.NE, done);
            a.movByte(Mem.rip(flagSymbol), 1);
            ClassModel model = pool.get(type);
            String superName = pool.superName(model);
            if (superName != null && initializers.contains(superName) && !model.flags().has(AccessFlag.INTERFACE)) {
                a.call(initializerSymbol(superName));
            }
            if (pool.declaredMethod(model, "<clinit>", "()V") != null) {
                a.call(methodSymbol(type, "<clinit>", "()V"));
            }
            a.bind(done);
            a.ret();
            image.define(initializerSymbol(type), text, start, text.size() - start, Image.SymbolType.FUNC);
        }
    }

    String requireMethod(ClassPool.ResolvedMethod m) {
        String symbol = methodSymbol(m.ownerName(), m.name(), m.descriptor());
        if (queuedMethods.add(symbol)) {
            worklist.addLast(m);
        }
        return symbol;
    }

    /** Records a dispatched call; every reachable override becomes reachable in turn. */
    void requireVirtual(String owner, String name, String descriptor) {
        virtualCalls.add(new VirtualCall(owner, name, descriptor));
    }

    /** Records a call through an interface and returns its itable index. */
    int requireInterfaceCall(String iface, String name, String descriptor) {
        interfaceCalls.add(new VirtualCall(iface, name, descriptor));
        return selectors.computeIfAbsent(new Vtables.Key(name, descriptor), k -> selectors.size());
    }

    /**
     * Rapid-type-analysis-style closure: for each dispatched call, compile the implementation each
     * reachable subclass would select. New methods can reach new classes, so the caller iterates.
     */
    private void resolveDispatchTargets() {
        for (VirtualCall call : List.copyOf(virtualCalls)) {
            for (String c : List.copyOf(classes)) {
                if (pool.get(c).flags().has(AccessFlag.INTERFACE) || !pool.isSubclass(c, call.owner())) {
                    continue;
                }
                ClassPool.ResolvedMethod impl = pool.findMethod(c, call.name(), call.descriptor());
                if (impl != null && !impl.is(AccessFlag.ABSTRACT) && !impl.is(AccessFlag.STATIC)) {
                    requireMethod(impl);
                }
            }
        }
        for (VirtualCall call : List.copyOf(interfaceCalls)) {
            for (String c : List.copyOf(classes)) {
                if (pool.get(c).flags().has(AccessFlag.INTERFACE) || !pool.allInterfaces(c).contains(call.owner())) {
                    continue;
                }
                ClassPool.ResolvedMethod impl = pool.selectMethod(c, call.name(), call.descriptor());
                if (impl != null && !impl.is(AccessFlag.ABSTRACT)) {
                    requireMethod(impl);
                }
            }
        }
    }

    String requireMethod(String owner, String name, String descriptor) {
        return requireMethod(pool.resolveMethod(owner, name, descriptor));
    }

    String requireStatic(ClassPool.ResolvedField f) {
        requireClass(f.ownerName());
        return staticSymbol(f.ownerName(), f.name());
    }

    void recordExceptionTable(String methodSymbol, int frameBytes, List<Handler> handlers) {
        exceptionTables.put(methodSymbol, new ExceptionTable(frameBytes, handlers));
    }

    void recordLines(String methodSymbol, String sourceFile, List<int[]> lines) {
        lineInfo.put(methodSymbol, new LineInfo(sourceFile, lines));
    }

    /**
     * For backtraces and exception dispatch (duke.rt.Backtrace, duke.rt.Exceptions). Header: entry
     * count (u64). Each 56-byte entry, sorted by address: start, size (u32), line count (u32), name
     * String, source file String or 0, line table or 0, exception table or 0, flags (u32, see
     * methodFlags) and padding. A line table is
     * (code offset u32, line u32) pairs in code order. An exception table is a row count (u32) and
     * the frame's local-variable bytes below rbp (u32), then 24-byte rows: start, end, handler
     * (u32 code offsets, plus u32 padding) and the catch type's TIB, 0 meaning any.
     */
    private void emitMethodTable() {
        List<Image.Symbol> functions = new ArrayList<>();
        for (Image.Symbol sym : image.symbols()) {
            if (sym.section() == image.text && sym.type() == Image.SymbolType.FUNC) {
                functions.add(sym);
            }
        }
        functions.sort((x, y) -> Integer.compare(x.offset(), y.offset()));

        Section rodata = image.rodata;
        rodata.align(8);
        for (Image.Symbol f : functions) {
            LineInfo info = lineInfo.get(f.name());
            if (info != null && !info.lines().isEmpty()) {
                int start = rodata.size();
                for (int[] pair : info.lines()) {
                    rodata.emit32(pair[0]);
                    rodata.emit32(pair[1]);
                }
                image.define("lines:" + f.name(), rodata, start, rodata.size() - start, Image.SymbolType.OBJECT);
            }
            ExceptionTable exceptions = exceptionTables.get(f.name());
            if (exceptions != null) {
                rodata.align(8);
                int start = rodata.size();
                rodata.emit32(exceptions.handlers().size());
                rodata.emit32(exceptions.frameBytes());
                for (Handler h : exceptions.handlers()) {
                    rodata.emit32(h.start());
                    rodata.emit32(h.end());
                    rodata.emit32(h.handler());
                    rodata.emit32(0);
                    emitPointer(rodata, h.catchTib());
                }
                image.define("exceptions:" + f.name(), rodata, start, rodata.size() - start, Image.SymbolType.OBJECT);
            }
        }
        rodata.align(8);
        int table = rodata.size();
        rodata.emit64(functions.size());
        for (Image.Symbol f : functions) {
            LineInfo info = lineInfo.get(f.name());
            boolean hasLines = info != null && !info.lines().isEmpty();
            rodata.emitReloc(Reloc.Kind.ABS64, f.name(), 0);
            rodata.emit32(f.size());
            rodata.emit32(hasLines ? info.lines().size() : 0);
            // Like Java stack traces: no descriptor, the line number disambiguates overloads.
            int paren = f.name().indexOf('(');
            emitPointer(rodata, requireString(javaName(paren < 0 ? f.name() : f.name().substring(0, paren))));
            emitPointer(rodata, info == null || info.sourceFile() == null ? null : requireString(info.sourceFile()));
            emitPointer(rodata, hasLines ? "lines:" + f.name() : null);
            emitPointer(rodata, exceptionTables.containsKey(f.name()) ? "exceptions:" + f.name() : null);
            rodata.emit32(methodFlags(f.name()));
            rodata.emit32(0);
        }
        image.define(METHOD_TABLE, rodata, table, rodata.size() - table, Image.SymbolType.OBJECT);
    }

    /** Hidden from stack traces: runtime plumbing and the constructors of the exception being built. */
    static final int METHOD_HIDDEN = 1;
    /** Unwinding stops here: exceptions don't propagate out of interrupt handlers. */
    static final int METHOD_INTERRUPT_ENTRY = 2;

    private int methodFlags(String symbol) {
        if (symbol.equals("interrupt.common")) {
            return METHOD_INTERRUPT_ENTRY;
        }
        if (symbol.equals(STACK_OVERFLOW)) {
            return METHOD_HIDDEN;
        }
        int dot = symbol.indexOf('.');
        int paren = symbol.indexOf('(');
        if (dot < 0 || paren < 0 || pool.find(symbol.substring(0, dot)) == null) {
            return 0;
        }
        String owner = symbol.substring(0, dot);
        String name = symbol.substring(dot + 1, paren);
        boolean throwableSetup = pool.isSubclass(owner, "java/lang/Throwable")
                && (name.equals("<init>") || name.equals("fillInStackTrace"));
        boolean plumbing = symbol.equals(STACK_OVERFLOW) || owner.equals("duke/rt/Runtime") || owner.equals("duke/rt/Types")
                || owner.equals("duke/rt/Backtrace") || owner.equals("duke/rt/Exceptions");
        return throwableSetup || plumbing ? METHOD_HIDDEN : 0;
    }

    void requireStackOverflowStub() {
        if (!stackOverflowStub) {
            stackOverflowStub = true;
            requireMethod("duke/rt/Runtime", "stackOverflow", "()V");
            requireMethod("duke/rt/Runtime", "stackExhausted", "()V");
        }
    }

    /**
     * Called from a prologue that found rsp below the limit. The first time, it lowers the limit to
     * the emergency mark and tail-jumps to Runtime.stackOverflow, which throws from inside the
     * reserve. Overflowing again before the unwinder resets the limit means even the reserve is
     * gone: checks are disabled and Runtime.stackExhausted panics.
     */
    private void emitStackOverflowStub() {
        image.data.align(8);
        int limit = image.data.size();
        image.data.emit64(0);
        image.define(STACK_LIMIT, image.data, limit, 8, Image.SymbolType.OBJECT);

        Section text = image.text;
        text.align(16);
        int start = text.size();
        X64 a = new X64(text);
        X64.Label exhausted = new X64.Label();
        a.lea(Reg.RAX, Mem.rip("boot.stack", STACK_EMERGENCY));
        a.alu(X64.Alu.CMP, true, Mem.rip(STACK_LIMIT), Reg.RAX);
        a.jcc(Cond.E, exhausted);
        a.store(8, Mem.rip(STACK_LIMIT), Reg.RAX);
        a.jmp(methodSymbol("duke/rt/Runtime", "stackOverflow", "()V"));
        a.bind(exhausted);
        a.alu(X64.Alu.XOR, false, Reg.RAX, Reg.RAX);
        a.store(8, Mem.rip(STACK_LIMIT), Reg.RAX);
        a.jmp(methodSymbol("duke/rt/Runtime", "stackExhausted", "()V"));
        image.define(STACK_OVERFLOW, text, start, text.size() - start, Image.SymbolType.FUNC);
    }

    String requireInterruptStubs() {
        if (!interruptStubs) {
            interruptStubs = true;
            requireMethod(INTERRUPTS, "dispatch", "(J)V");
        }
        return INTERRUPT_STUBS;
    }

    /**
     * One entry stub per vector, then a common path that saves every general-purpose register,
     * calls the Java dispatcher with the frame address, restores and returns with iretq.
     * Frame layout from the address passed: r15 ... rax (15 slots, r15 lowest), vector, error code,
     * then the CPU's rip, cs, rflags, rsp, ss.
     */
    private void emitInterruptStubs() {
        Section text = image.text;
        X64 a = new X64(text);
        text.align(16);
        int common = text.size();
        a.cld();
        for (Reg r : SAVED_REGISTERS) {
            a.push(r);
        }
        a.mov(Reg.RAX, Reg.RSP);
        a.push(Reg.RAX);
        a.push(Reg.RAX);
        a.call(methodSymbol(INTERRUPTS, "dispatch", "(J)V"));
        a.aluImm(X64.Alu.ADD, true, Reg.RSP, 16);
        for (int i = SAVED_REGISTERS.length - 1; i >= 0; i--) {
            a.pop(SAVED_REGISTERS[i]);
        }
        a.aluImm(X64.Alu.ADD, true, Reg.RSP, 16);
        a.iretq();
        image.define("interrupt.common", text, common, text.size() - common, Image.SymbolType.FUNC);

        for (int vector = 0; vector < 256; vector++) {
            text.align(16);
            int start = text.size();
            if (!ERROR_CODE_VECTORS.contains(vector)) {
                a.pushImm(0);
            }
            a.pushImm(vector);
            a.jmp("interrupt.common");
            image.define("interrupt." + vector, text, start, text.size() - start, Image.SymbolType.FUNC);
        }

        Section rodata = image.rodata;
        rodata.align(8);
        int table = rodata.size();
        for (int vector = 0; vector < 256; vector++) {
            rodata.emitReloc(Reloc.Kind.ABS64, "interrupt." + vector, 0);
        }
        image.define(INTERRUPT_STUBS, rodata, table, rodata.size() - table, Image.SymbolType.OBJECT);
    }

    /** A TIB for a type named the way class entries name it: internal name, or descriptor for arrays. */
    String requireTib(String type) {
        if (type.startsWith("[")) {
            return requireArrayTib(type);
        }
        pool.get(type);
        tibs.add(type);
        return tibSymbol(type);
    }

    /** {@code type} is an array descriptor such as {@code [I} or {@code [Ljava/lang/String;}. */
    String requireArrayTib(String type) {
        tibs.add(type);
        return tibSymbol(type);
    }

    /** Emits the per-site table {@code Heap.allocateMultiArray} walks; layout documented there. */
    String requireMultiArrayDescriptor(String type, int dimensions) {
        Section rodata = image.rodata;
        rodata.align(8);
        int start = rodata.size();
        rodata.emit32(dimensions);
        rodata.emit32(0);
        for (int level = 0; level < dimensions; level++) {
            String levelType = type.substring(level);
            rodata.emitReloc(Reloc.Kind.ABS64, requireArrayTib(levelType), 0);
            rodata.emit64(Layouts.width(levelType.substring(1)));
        }
        String symbol = "multianewarray:" + multiArraySites++;
        image.define(symbol, rodata, start, rodata.size() - start, Image.SymbolType.OBJECT);
        return symbol;
    }

    String requireString(String value) {
        String symbol = strings.get(value);
        if (symbol == null) {
            for (int i = 0; i < value.length(); i++) {
                if (value.charAt(i) > 0xFF) {
                    throw new CompileException("string literal has non-Latin-1 characters: \"" + value + "\"");
                }
            }
            requireClass(STRING_CLASS);
            tibs.add(BYTE_ARRAY);
            symbol = "str:" + strings.size();
            strings.put(value, symbol);
        }
        return symbol;
    }

    private void emitStatics() {
        Section data = image.data;
        for (String name : classes) {
            for (FieldModel f : pool.get(name).fields()) {
                if (!f.flags().has(AccessFlag.STATIC)) {
                    continue;
                }
                data.align(8);
                int offset = data.size();
                ConstantDesc initial = constantValue(f);
                switch (initial) {
                    case null -> data.emit64(0);
                    case Integer i -> data.emit64(i);
                    case Long l -> data.emit64(l);
                    case String s -> data.emitReloc(Reloc.Kind.ABS64, requireString(s), 0);
                    default -> throw new CompileException("unsupported constant " + initial + " for " + name + "." + f.fieldName());
                }
                image.define(staticSymbol(name, f.fieldName().stringValue()), data, offset, 8, Image.SymbolType.OBJECT);
            }
        }
    }

    private static ConstantDesc constantValue(FieldModel f) {
        return f.findAttribute(Attributes.constantValue())
                .map(ConstantValueAttribute::constant)
                .map(c -> c.constantValue())
                .orElse(null);
    }

    /** String literals live in .data, not .rodata: String may cache things in its own fields later. */
    private void emitStrings() {
        Section data = image.data;
        Layouts.ClassLayout layout = layouts.of(STRING_CLASS);
        int valueOffset = layout.offsetOf("value");
        for (Map.Entry<String, String> e : strings.entrySet()) {
            String symbol = e.getValue();
            byte[] bytes = e.getKey().getBytes(StandardCharsets.ISO_8859_1);

            data.align(8);
            int start = data.size();
            data.emitReloc(Reloc.Kind.ABS64, tibSymbol(STRING_CLASS), 0);
            for (int pos = Layouts.HEADER_SIZE; pos < layout.size(); ) {
                if (pos == valueOffset) {
                    data.emitReloc(Reloc.Kind.ABS64, symbol + ".value", 0);
                    pos += 8;
                } else {
                    data.emit8(0);
                    pos++;
                }
            }
            image.define(symbol, data, start, layout.size(), Image.SymbolType.OBJECT);

            data.align(8);
            start = data.size();
            data.emitReloc(Reloc.Kind.ABS64, tibSymbol(BYTE_ARRAY), 0);
            data.emit32(bytes.length);
            data.emit32(0);
            data.emitBytes(bytes);
            image.define(symbol + ".value", data, start, Layouts.ARRAY_DATA_OFFSET + bytes.length, Image.SymbolType.OBJECT);
        }
    }

    /** Adds every TIB the emitted ones point at: superclasses, Object for arrays, element types. */
    private void closeTibs() {
        Deque<String> pending = new ArrayDeque<>(tibs);
        while (!pending.isEmpty()) {
            String type = pending.removeFirst();
            List<String> deps = new ArrayList<>();
            if (type.startsWith("[")) {
                deps.add(ClassPool.OBJECT);
                String element = elementType(type);
                if (element != null) {
                    deps.add(element);
                }
            } else {
                String superName = pool.superName(pool.get(type));
                if (superName != null) {
                    deps.add(superName);
                }
                deps.addAll(pool.allInterfaces(type));
            }
            for (String dep : deps) {
                if (tibs.add(dep)) {
                    pending.addLast(dep);
                }
            }
        }
        for (String type : tibs) {
            requireString(javaName(type));
        }
    }

    /** The element type of a reference array, or null for a primitive array. */
    static String elementType(String arrayType) {
        String element = arrayType.substring(1);
        if (element.startsWith("[")) {
            return element;
        }
        if (element.startsWith("L")) {
            return element.substring(1, element.length() - 1);
        }
        return null;
    }

    /** The name Class.getName() would report: dots for classes, descriptors for arrays. */
    static String javaName(String type) {
        return type.replace('/', '.');
    }

    /**
     * TIB layout (offsets in Layouts). A TIB is also the type's java.lang.Class instance: word 0 is
     * an ordinary object header. They live in .rodata, so Class can never have instance fields.
     * <pre>
     * [0]  object header: the TIB of java.lang.Class
     * [8]  super TIB, or 0 for Object; Object for arrays
     * [16] instance size, or element size for arrays (u32)
     * [20] flags: array, interface, reference array (u32)
     * [24] element TIB for reference arrays, else 0
     * [32] name, a String
     * [40] 0-terminated list of every interface the type implements, or 0 if none
     * [48] itable: one code pointer per interface selector, or 0 for classes without interfaces
     * [56] vtable: one code pointer per Vtables slot, 0 where nothing dispatches to it
     * </pre>
     */
    private void emitTibs() {
        Section rodata = image.rodata;
        for (String type : tibs) {
            if (type.startsWith("[") || pool.allInterfaces(type).isEmpty()) {
                continue;
            }
            rodata.align(8);
            int start = rodata.size();
            for (String iface : pool.allInterfaces(type)) {
                rodata.emitReloc(Reloc.Kind.ABS64, tibSymbol(iface), 0);
            }
            rodata.emit64(0);
            image.define(interfacesSymbol(type), rodata, start, rodata.size() - start, Image.SymbolType.OBJECT);
            if (!selectors.isEmpty() && !pool.get(type).flags().has(AccessFlag.INTERFACE)) {
                emitItable(type);
            }
        }
        for (String type : tibs) {
            boolean array = type.startsWith("[");
            String element = array ? elementType(type) : null;
            boolean isInterface = !array && pool.get(type).flags().has(AccessFlag.INTERFACE);
            String superName = array ? ClassPool.OBJECT : pool.superName(pool.get(type));
            int flags = (array ? Layouts.TIB_FLAG_ARRAY : 0)
                    | (isInterface ? Layouts.TIB_FLAG_INTERFACE : 0)
                    | (element != null ? Layouts.TIB_FLAG_REFERENCE_ARRAY : 0);

            rodata.align(8);
            int start = rodata.size();
            emitPointer(rodata, tibSymbol(CLASS_CLASS));
            emitPointer(rodata, superName == null ? null : tibSymbol(superName));
            rodata.emit32(array ? Layouts.width(type.substring(1)) : layouts.of(type).size());
            rodata.emit32(flags);
            emitPointer(rodata, element == null ? null : tibSymbol(element));
            emitPointer(rodata, strings.get(javaName(type)));
            emitPointer(rodata, image.isDefined(interfacesSymbol(type)) ? interfacesSymbol(type) : null);
            emitPointer(rodata, image.isDefined(itableSymbol(type)) ? itableSymbol(type) : null);
            String dispatchType = array || isInterface ? ClassPool.OBJECT : type;
            for (Vtables.Key key : vtables.layout(dispatchType)) {
                ClassPool.ResolvedMethod impl = pool.findMethod(dispatchType, key.name(), key.descriptor());
                String symbol = impl == null ? null : methodSymbol(impl.ownerName(), impl.name(), impl.descriptor());
                boolean compiled = symbol != null && queuedMethods.contains(symbol) && !impl.is(AccessFlag.ABSTRACT);
                emitPointer(rodata, compiled ? symbol : null);
            }
            image.define(tibSymbol(type), rodata, start, rodata.size() - start, Image.SymbolType.OBJECT);
        }
    }

    /** Entries stay 0 where the class doesn't implement the selector or nothing calls it. */
    private void emitItable(String type) {
        Section rodata = image.rodata;
        rodata.align(8);
        int start = rodata.size();
        for (Vtables.Key key : selectors.keySet()) {
            ClassPool.ResolvedMethod impl = pool.selectMethod(type, key.name(), key.descriptor());
            String symbol = impl == null ? null : methodSymbol(impl.ownerName(), impl.name(), impl.descriptor());
            boolean compiled = symbol != null && queuedMethods.contains(symbol) && !impl.is(AccessFlag.ABSTRACT);
            emitPointer(rodata, compiled ? symbol : null);
        }
        image.define(itableSymbol(type), rodata, start, rodata.size() - start, Image.SymbolType.OBJECT);
    }

    private static void emitPointer(Section section, String symbol) {
        if (symbol == null) {
            section.emit64(0);
        } else {
            section.emitReloc(Reloc.Kind.ABS64, symbol, 0);
        }
    }

    /** Switches to our own stack, initializes the entry class, then calls the entry point. */
    private void emitBootStub(String entryClass, String mainSymbol) {
        image.bss.align(16);
        int stackOffset = image.bss.size();
        image.bss.reserve(BOOT_STACK_SIZE);
        image.define("boot.stack", image.bss, stackOffset, BOOT_STACK_SIZE, Image.SymbolType.OBJECT);

        Section text = image.text;
        text.align(16);
        int start = text.size();
        X64 a = new X64(text);
        a.lea(Reg.RSP, Mem.rip("boot.stack", BOOT_STACK_SIZE));
        a.alu(X64.Alu.XOR, false, Reg.RBP, Reg.RBP);
        if (stackOverflowStub) {
            a.lea(Reg.RAX, Mem.rip("boot.stack", STACK_RESERVE));
            a.store(8, Mem.rip(STACK_LIMIT), Reg.RAX);
        }
        if (initializers.contains(entryClass)) {
            a.call(initializerSymbol(entryClass));
        }
        a.call(mainSymbol);
        X64.Label hang = new X64.Label();
        a.bind(hang);
        a.cli();
        a.hlt();
        a.jmp(hang);
        image.define(ENTRY_SYMBOL, text, start, text.size() - start, Image.SymbolType.FUNC);
    }

    /** Costs nothing in the file: .bss is only memsz. Replaced by a real heap in #16. */
    private void emitHeapArena() {
        image.bss.align(4096);
        int offset = image.bss.size();
        image.bss.reserve(HEAP_ARENA_SIZE);
        image.define(HEAP_ARENA, image.bss, offset, HEAP_ARENA_SIZE, Image.SymbolType.OBJECT);
    }

    /** See PROTOCOL.md in Limine-Bootloader/limine-protocol, "Requests Delimiters" and "Base Revisions". */
    private void emitLimineRequests() {
        Section data = image.data;
        data.align(8);
        data.emit64(0xf6b8f4b39de7d1aeL);
        data.emit64(0xfab91a6940fcb9cfL);
        data.emit64(0x785c6ed015d3e316L);
        data.emit64(0x181e920a7852b9d9L);
        int revision = data.size();
        data.emit64(0xf9562b2d5c95a6c8L);
        data.emit64(0x6a7b384944536bdcL);
        data.emit64(LIMINE_BASE_REVISION);
        image.define("limine.base_revision", data, revision, 24, Image.SymbolType.OBJECT);
        data.emit64(0xadc0e0531bb10d03L);
        data.emit64(0x9572709f31764c62L);
    }
}
