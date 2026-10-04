package duke.compiler;

import static duke.compiler.asm.Reg.RAX;
import static duke.compiler.asm.Reg.RBP;
import static duke.compiler.asm.Reg.RCX;
import static duke.compiler.asm.Reg.RDX;
import static duke.compiler.asm.Reg.RSI;
import static duke.compiler.asm.Reg.RSP;

import duke.compiler.asm.Cond;
import duke.compiler.asm.Mem;
import duke.compiler.asm.Reg;
import duke.compiler.asm.X64;
import duke.compiler.asm.X64.Alu;
import duke.compiler.asm.X64.Shift;
import duke.compiler.image.Image;
import duke.compiler.image.Section;
import java.lang.classfile.Attributes;
import java.lang.classfile.CodeElement;
import java.lang.classfile.Instruction;
import java.lang.classfile.Label;
import java.lang.classfile.Opcode;
import java.lang.classfile.TypeKind;
import java.lang.classfile.attribute.CodeAttribute;
import java.lang.classfile.instruction.ArrayLoadInstruction;
import java.lang.classfile.instruction.ArrayStoreInstruction;
import java.lang.classfile.instruction.BranchInstruction;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.ConvertInstruction;
import java.lang.classfile.instruction.ExceptionCatch;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.IncrementInstruction;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LabelTarget;
import java.lang.classfile.instruction.LineNumber;
import java.lang.classfile.instruction.LoadInstruction;
import java.lang.classfile.instruction.LookupSwitchInstruction;
import java.lang.classfile.instruction.NewMultiArrayInstruction;
import java.lang.classfile.instruction.NewObjectInstruction;
import java.lang.classfile.instruction.NewPrimitiveArrayInstruction;
import java.lang.classfile.instruction.NewReferenceArrayInstruction;
import java.lang.classfile.instruction.NopInstruction;
import java.lang.classfile.instruction.OperatorInstruction;
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.classfile.instruction.StackInstruction;
import java.lang.classfile.instruction.StoreInstruction;
import java.lang.classfile.instruction.SwitchCase;
import java.lang.classfile.instruction.TableSwitchInstruction;
import java.lang.classfile.instruction.ThrowInstruction;
import java.lang.classfile.instruction.TypeCheckInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Template code generator: each bytecode becomes a fixed x86 sequence operating on the machine
 * stack, which doubles as the JVM operand stack.
 *
 * <p>Every JVM stack or local slot is one 8-byte machine slot, so category-2 values (long) take
 * two: the value in the lower-numbered local / deeper stack slot, and a padding slot above it.
 * Keeping the JVM's slot model 1:1 makes dup2, pop2 and friends trivially correct. Int values in
 * a slot have undefined upper 32 bits, so anything consuming an int as 64 bits must extend it.
 *
 * <p>Calling convention: the caller leaves arguments on its operand stack (first argument
 * deepest) and pops them after the call; results come back in rax. Argument slots double as the
 * callee's first locals, addressed above rbp.
 */
final class MethodCompiler {

    private static final String RUNTIME = "duke/rt/Runtime";
    private static final String MAGIC = "duke/rt/Magic";
    private static final String HEAP = "duke/rt/Heap";
    private static final String TYPES = "duke/rt/Types";
    private static final String EXCEPTIONS = "duke/rt/Exceptions";

    private final Compiler program;
    private final ClassPool pool;
    private final ClassPool.ResolvedMethod method;
    private final X64 a;
    private final Map<Label, X64.Label> labels = new IdentityHashMap<>();
    private int argSlots;
    private int line = -1;
    private final List<ExceptionCatch> catches = new ArrayList<>();
    /** (code offset, source line) pairs, in code order. */
    private final List<int[]> lines = new ArrayList<>();
    MethodCompiler(Compiler program, ClassPool.ResolvedMethod method) {
        this.program = program;
        this.pool = program.pool();
        this.method = method;
        this.a = new X64(program.image().text);
    }

    void compile() {
        if (method.is(AccessFlag.NATIVE)) {
            throw error("native methods are only allowed in " + MAGIC);
        }
        if (method.is(AccessFlag.ABSTRACT)) {
            throw error("abstract method reached without virtual dispatch support");
        }
        if (method.is(AccessFlag.SYNCHRONIZED)) {
            throw error("synchronized methods are not supported yet");
        }
        CodeAttribute code = (CodeAttribute) method.method().code().orElseThrow();
        argSlots = argSlots(method.method().methodTypeSymbol()) + (method.is(AccessFlag.STATIC) ? 0 : 1);
        int extraLocals = code.maxLocals() - argSlots;

        Section text = program.image().text;
        text.align(16);
        int start = a.position();
        a.push(RBP);
        a.mov(RBP, RSP);
        if (extraLocals > 0) {
            a.aluImm(Alu.SUB, true, RSP, 8 * extraLocals);
        }
        stackCheck();

        for (CodeElement e : code) {
            switch (e) {
                case ExceptionCatch c -> catches.add(c);
                case LabelTarget t -> a.bind(label(t.label()));
                case LineNumber n -> {
                    line = n.line();
                    lines.add(new int[] {a.position() - start, line});
                }
                case Instruction i -> instruction(i);
                default -> { }
            }
        }
        checkLabelsBound();

        String symbol = Compiler.methodSymbol(method.ownerName(), method.name(), method.descriptor());
        program.image().define(symbol, text, start, a.position() - start, Image.SymbolType.FUNC);
        String sourceFile = method.owner().findAttribute(Attributes.sourceFile())
                .map(f -> f.sourceFile().stringValue()).orElse(null);
        program.recordLines(symbol, sourceFile, lines);
        recordExceptionTable(symbol, start, extraLocals);
    }

    private CompileException error(String message) {
        String where = method.ownerName() + "." + method.name() + method.descriptor();
        if (line >= 0) {
            where += " (line " + line + ")";
        }
        return new CompileException(where + ": " + message);
    }

    private X64.Label label(Label l) {
        return labels.computeIfAbsent(l, k -> new X64.Label());
    }

    private Mem local(int slot) {
        if (slot < argSlots) {
            return Mem.at(RBP, 16 + 8 * (argSlots - 1 - slot));
        }
        return Mem.at(RBP, -8 * (slot - argSlots + 1));
    }

    private static int argSlots(MethodTypeDesc type) {
        int slots = 0;
        for (ClassDesc p : type.parameterList()) {
            slots += TypeKind.from(p).slotSize();
        }
        return slots;
    }

    private void instruction(Instruction i) {
        switch (i) {
            case LoadInstruction l -> {
                a.push(local(l.slot()));
                if (l.typeKind().slotSize() == 2) {
                    a.push(local(l.slot() + 1));
                }
            }
            case StoreInstruction s -> {
                if (s.typeKind().slotSize() == 2) {
                    a.pop(local(s.slot() + 1));
                }
                a.pop(local(s.slot()));
            }
            case IncrementInstruction inc -> a.aluImm(Alu.ADD, false, local(inc.slot()), inc.constant());
            case ConstantInstruction c -> constant(c);
            case OperatorInstruction op -> operator(op.opcode());
            case ConvertInstruction c -> convert(c.opcode());
            case BranchInstruction b -> branch(b.opcode(), label(b.target()));
            case TableSwitchInstruction t -> lookup(t.cases(), t.defaultTarget());
            case LookupSwitchInstruction l -> lookup(l.cases(), l.defaultTarget());
            case StackInstruction s -> stack(s.opcode());
            case FieldInstruction f -> field(f);
            case InvokeInstruction inv -> invoke(inv);
            case InvokeDynamicInstruction indy -> invokeDynamic(indy);
            case ReturnInstruction r -> {
                switch (r.typeKind().slotSize()) {
                    case 1 -> a.pop(RAX);
                    case 2 -> popLong(RAX);
                    default -> { }
                }
                a.leave();
                a.ret();
            }
            case ArrayLoadInstruction l -> arrayLoad(l.typeKind());
            case ArrayStoreInstruction s -> arrayStore(s.typeKind());
            case ThrowInstruction t -> {
                a.pop(RAX);
                nullCheck(RAX);
                a.push(RAX);
                ensureInitialized(EXCEPTIONS);
                a.call(program.requireMethod(EXCEPTIONS, "raise", "(Ljava/lang/Throwable;)V"));
                a.ud2();
            }
            case NewObjectInstruction n -> newObject(n.className().asInternalName());
            case NewPrimitiveArrayInstruction n -> newArray("[" + n.typeKind().upperBound().descriptorString());
            case NewReferenceArrayInstruction n -> newArray("[" + descriptorOf(n.componentType().asInternalName()));
            case NewMultiArrayInstruction n -> newMultiArray(n.arrayType().asInternalName(), n.dimensions());
            case TypeCheckInstruction t -> typeCheck(t.opcode(), program.requireTib(t.type().asInternalName()));
            case NopInstruction n -> { }
            default -> throw error("unsupported bytecode " + i.opcode().name().toLowerCase());
        }
    }

    private void popLong(Reg r) {
        a.aluImm(Alu.ADD, true, RSP, 8);
        a.pop(r);
    }

    private void pushLong(Reg r) {
        a.push(r);
        a.push(r);
    }

    private void constant(ConstantInstruction c) {
        if (c.opcode() == Opcode.ACONST_NULL) {
            a.pushImm(0);
            return;
        }
        switch (c.constantValue()) {
            case Integer v -> a.pushImm(v);
            case Long v -> {
                a.movImm64(RAX, v);
                pushLong(RAX);
            }
            case String s -> {
                a.lea(RAX, Mem.rip(program.requireString(s)));
                a.push(RAX);
            }
            case ClassDesc type when !type.isPrimitive() -> {
                // A TIB is its type's Class object.
                String name = type.isArray() ? type.descriptorString() : type.descriptorString()
                        .substring(1, type.descriptorString().length() - 1);
                a.lea(RAX, Mem.rip(program.requireTib(name)));
                a.push(RAX);
            }
            default -> throw error("unsupported constant " + c.constantValue() + " (floating point is not supported yet)");
        }
    }

    private void operator(Opcode op) {
        switch (op) {
            case IADD -> intBinary(Alu.ADD);
            case ISUB -> intBinary(Alu.SUB);
            case IAND -> intBinary(Alu.AND);
            case IOR -> intBinary(Alu.OR);
            case IXOR -> intBinary(Alu.XOR);
            case LADD -> longBinary(Alu.ADD);
            case LSUB -> longBinary(Alu.SUB);
            case LAND -> longBinary(Alu.AND);
            case LOR -> longBinary(Alu.OR);
            case LXOR -> longBinary(Alu.XOR);
            case IMUL -> {
                a.pop(RCX);
                a.pop(RAX);
                a.imul(false, RAX, RCX);
                a.push(RAX);
            }
            case LMUL -> {
                popLong(RCX);
                popLong(RAX);
                a.imul(true, RAX, RCX);
                pushLong(RAX);
            }
            case IDIV -> divide(false, false);
            case IREM -> divide(false, true);
            case LDIV -> divide(true, false);
            case LREM -> divide(true, true);
            case INEG -> a.neg(false, Mem.at(RSP));
            case LNEG -> a.neg(true, Mem.at(RSP, 8));
            case ISHL -> intShift(Shift.SHL);
            case ISHR -> intShift(Shift.SAR);
            case IUSHR -> intShift(Shift.SHR);
            case LSHL -> longShift(Shift.SHL);
            case LSHR -> longShift(Shift.SAR);
            case LUSHR -> longShift(Shift.SHR);
            case LCMP -> {
                popLong(RCX);
                popLong(RAX);
                a.alu(Alu.CMP, true, RAX, RCX);
                a.setcc(Cond.G, RAX);
                a.setcc(Cond.L, RCX);
                a.movzx8(RAX, RAX);
                a.movzx8(RCX, RCX);
                a.alu(Alu.SUB, false, RAX, RCX);
                a.push(RAX);
            }
            case ARRAYLENGTH -> {
                a.pop(RAX);
                nullCheck(RAX);
                a.load(4, false, RAX, Mem.at(RAX, Layouts.ARRAY_LENGTH_OFFSET));
                a.push(RAX);
            }
            default -> throw error("unsupported operator " + op.name().toLowerCase() + " (floating point is not supported yet)");
        }
    }

    private void intBinary(Alu op) {
        a.pop(RCX);
        a.pop(RAX);
        a.alu(op, false, RAX, RCX);
        a.push(RAX);
    }

    private void longBinary(Alu op) {
        popLong(RCX);
        popLong(RAX);
        a.alu(op, true, RAX, RCX);
        pushLong(RAX);
    }

    private void intShift(Shift kind) {
        a.pop(RCX);
        a.pop(RAX);
        a.shiftCl(kind, false, RAX);
        a.push(RAX);
    }

    private void longShift(Shift kind) {
        a.pop(RCX);
        popLong(RAX);
        a.shiftCl(kind, true, RAX);
        pushLong(RAX);
    }

    /** idiv traps on MIN_VALUE / -1 where Java wraps, so -1 divisors take a separate path. */
    private void divide(boolean wide, boolean remainder) {
        if (wide) {
            popLong(RCX);
            popLong(RAX);
        } else {
            a.pop(RCX);
            a.pop(RAX);
        }
        X64.Label nonZero = new X64.Label();
        a.test(wide, RCX, RCX);
        a.jcc(Cond.NE, nonZero);
        a.call(program.requireMethod(RUNTIME, "divideByZero", "()V"));
        a.bind(nonZero);
        X64.Label normal = new X64.Label();
        X64.Label done = new X64.Label();
        a.aluImm(Alu.CMP, wide, RCX, -1);
        a.jcc(Cond.NE, normal);
        if (remainder) {
            a.alu(Alu.XOR, false, RAX, RAX);
        } else {
            a.neg(wide, RAX);
        }
        a.jmp(done);
        a.bind(normal);
        if (wide) {
            a.cqo();
        } else {
            a.cdq();
        }
        a.idiv(wide, RCX);
        if (remainder) {
            a.mov(RAX, RDX);
        }
        a.bind(done);
        if (wide) {
            pushLong(RAX);
        } else {
            a.push(RAX);
        }
    }

    private void convert(Opcode op) {
        switch (op) {
            case I2L -> {
                a.pop(RAX);
                a.movsxd(RAX, RAX);
                pushLong(RAX);
            }
            case L2I -> {
                popLong(RAX);
                a.push(RAX);
            }
            case I2B -> narrow(() -> a.movsx8(RAX, RAX));
            case I2C -> narrow(() -> a.movzx16(RAX, RAX));
            case I2S -> narrow(() -> a.movsx16(RAX, RAX));
            default -> throw error("unsupported conversion " + op.name().toLowerCase() + " (floating point is not supported yet)");
        }
    }

    private void narrow(Runnable extend) {
        a.pop(RAX);
        extend.run();
        a.push(RAX);
    }

    private void branch(Opcode op, X64.Label target) {
        switch (op) {
            case IFEQ, IFNE, IFLT, IFGE, IFGT, IFLE -> {
                a.pop(RAX);
                a.test(false, RAX, RAX);
                a.jcc(condition(op), target);
            }
            case IF_ICMPEQ, IF_ICMPNE, IF_ICMPLT, IF_ICMPGE, IF_ICMPGT, IF_ICMPLE, IF_ACMPEQ, IF_ACMPNE -> {
                a.pop(RCX);
                a.pop(RAX);
                a.alu(Alu.CMP, op == Opcode.IF_ACMPEQ || op == Opcode.IF_ACMPNE, RAX, RCX);
                a.jcc(condition(op), target);
            }
            case IFNULL, IFNONNULL -> {
                a.pop(RAX);
                a.test(true, RAX, RAX);
                a.jcc(op == Opcode.IFNULL ? Cond.E : Cond.NE, target);
            }
            case GOTO, GOTO_W -> a.jmp(target);
            default -> throw error("unsupported branch " + op.name().toLowerCase());
        }
    }

    private static Cond condition(Opcode op) {
        return switch (op) {
            case IFEQ, IF_ICMPEQ, IF_ACMPEQ -> Cond.E;
            case IFNE, IF_ICMPNE, IF_ACMPNE -> Cond.NE;
            case IFLT, IF_ICMPLT -> Cond.L;
            case IFGE, IF_ICMPGE -> Cond.GE;
            case IFGT, IF_ICMPGT -> Cond.G;
            case IFLE, IF_ICMPLE -> Cond.LE;
            default -> throw new IllegalArgumentException(op.name());
        };
    }

    /** Compare chain for both switch forms; a jump table can come with a real register allocator. */
    private void lookup(List<SwitchCase> cases, Label defaultTarget) {
        a.pop(RAX);
        for (SwitchCase c : cases) {
            a.aluImm(Alu.CMP, false, RAX, c.caseValue());
            a.jcc(Cond.E, label(c.target()));
        }
        a.jmp(label(defaultTarget));
    }

    private void stack(Opcode op) {
        switch (op) {
            case POP -> a.aluImm(Alu.ADD, true, RSP, 8);
            case POP2 -> a.aluImm(Alu.ADD, true, RSP, 16);
            case DUP -> a.push(Mem.at(RSP));
            case DUP2 -> {
                a.push(Mem.at(RSP, 8));
                a.push(Mem.at(RSP, 8));
            }
            case SWAP -> {
                a.pop(RAX);
                a.pop(RCX);
                a.push(RAX);
                a.push(RCX);
            }
            case DUP_X1 -> shuffle(2, RAX, RCX, RAX);
            case DUP_X2 -> shuffle(3, RAX, RDX, RCX, RAX);
            case DUP2_X1 -> shuffle(3, RCX, RAX, RDX, RCX, RAX);
            case DUP2_X2 -> shuffle(4, RCX, RAX, RSI, RDX, RCX, RAX);
            default -> throw error("unsupported stack op " + op.name().toLowerCase());
        }
    }

    /** Pops {@code count} slots into rax, rcx, rdx, rsi (top first), then pushes {@code pushes} in order. */
    private void shuffle(int count, Reg... pushes) {
        Reg[] regs = {RAX, RCX, RDX, RSI};
        for (int i = 0; i < count; i++) {
            a.pop(regs[i]);
        }
        for (Reg r : pushes) {
            a.push(r);
        }
    }

    private void field(FieldInstruction f) {
        String descriptor = f.type().stringValue();
        ClassPool.ResolvedField field = pool.resolveField(f.owner().asInternalName(), f.name().stringValue(), descriptor);
        boolean isStatic = field.field().flags().has(AccessFlag.STATIC);
        boolean wantStatic = f.opcode() == Opcode.GETSTATIC || f.opcode() == Opcode.PUTSTATIC;
        if (isStatic != wantStatic) {
            throw error("field " + field.ownerName() + "." + field.name() + (isStatic ? " is" : " is not") + " static");
        }
        int width = Layouts.width(descriptor);
        boolean signed = Layouts.signed(descriptor);
        boolean wide = descriptor.equals("J") || descriptor.equals("D");
        switch (f.opcode()) {
            case GETSTATIC -> {
                ensureInitialized(field.ownerName());
                a.load(width, signed, RAX, Mem.rip(program.requireStatic(field)));
                pushValue(RAX, wide);
            }
            case PUTSTATIC -> {
                ensureInitialized(field.ownerName());
                popValue(RAX, wide);
                a.store(width, Mem.rip(program.requireStatic(field)), RAX);
            }
            case GETFIELD -> {
                int offset = program.layouts().of(field.ownerName()).offsetOf(field.name());
                a.pop(RAX);
                nullCheck(RAX);
                a.load(width, signed, RAX, Mem.at(RAX, offset));
                pushValue(RAX, wide);
            }
            case PUTFIELD -> {
                int offset = program.layouts().of(field.ownerName()).offsetOf(field.name());
                popValue(RDX, wide);
                a.pop(RAX);
                nullCheck(RAX);
                a.store(width, Mem.at(RAX, offset), RDX);
            }
            default -> throw new IllegalStateException(f.opcode().name());
        }
    }

    private void pushValue(Reg r, boolean wide) {
        if (wide) {
            pushLong(r);
        } else {
            a.push(r);
        }
    }

    private void popValue(Reg r, boolean wide) {
        if (wide) {
            popLong(r);
        } else {
            a.pop(r);
        }
    }

    private void invoke(InvokeInstruction inv) {
        String owner = inv.owner().asInternalName();
        String name = inv.name().stringValue();
        String descriptor = inv.type().stringValue();
        MethodTypeDesc type = inv.typeSymbol();
        switch (inv.opcode()) {
            case INVOKESTATIC -> {
                if (owner.equals(MAGIC)) {
                    magic(name);
                    return;
                }
                ClassPool.ResolvedMethod m = pool.resolveMethod(owner, name, descriptor);
                if (!m.is(AccessFlag.STATIC)) {
                    throw error(m.ownerName() + "." + name + " is not static");
                }
                ensureInitialized(m.ownerName());
                call(m, type, argSlots(type));
            }
            case INVOKESPECIAL -> {
                ClassPool.ResolvedMethod m = pool.resolveMethod(owner, name, descriptor);
                program.requireClass(m.ownerName());
                call(m, type, argSlots(type) + 1);
            }
            case INVOKEVIRTUAL -> {
                ClassPool.ResolvedMethod m = pool.resolveMethod(owner, name, descriptor);
                if (m.is(AccessFlag.STATIC)) {
                    throw error(m.ownerName() + "." + name + " is static");
                }
                if (m.owner().flags().has(AccessFlag.INTERFACE)) {
                    // Inherited default method: only the itable knows which one this receiver runs.
                    interfaceCall(m.ownerName(), name, descriptor, type);
                    return;
                }
                boolean exact = m.is(AccessFlag.PRIVATE) || m.is(AccessFlag.FINAL)
                        || m.owner().flags().has(AccessFlag.FINAL) || !pool.isOverridden(m);
                int args = argSlots(type);
                a.load(8, false, RAX, Mem.at(RSP, 8 * args));
                nullCheck(RAX);
                program.requireClass(m.ownerName());
                if (exact) {
                    call(m, type, args + 1);
                } else {
                    int slot = program.vtables().slot(owner, name, descriptor);
                    program.requireVirtual(owner, name, descriptor);
                    a.load(8, false, RAX, Mem.at(RAX));
                    a.call(Mem.at(RAX, Layouts.TIB_VTABLE + 8 * slot));
                    afterCall(type, args + 1);
                }
            }
            case INVOKEINTERFACE -> {
                ClassPool.ResolvedMethod m = pool.resolveMethod(owner, name, descriptor);
                if (m.is(AccessFlag.PRIVATE)) {
                    int args = argSlots(type);
                    a.load(8, false, RAX, Mem.at(RSP, 8 * args));
                    nullCheck(RAX);
                    call(m, type, args + 1);
                } else {
                    interfaceCall(owner, name, descriptor, type);
                }
            }
            default -> throw error("unsupported call " + inv.opcode().name().toLowerCase() + " to " + owner + "." + name
                    + " (lambdas are not supported yet)");
        }
    }

    /**
     * JVMS 5.5 initialization trigger. Skipped when nothing would run, and inside the class itself
     * or a subclass, where the class is already initialized or being initialized.
     */
    private void ensureInitialized(String type) {
        program.requireClass(type);
        if (!program.needsInit(type) || pool.isSubclass(method.ownerName(), type)) {
            return;
        }
        String initializer = program.requireInitializer(type);
        X64.Label done = new X64.Label();
        a.cmpByte(Mem.rip("initialized:" + type), 0);
        a.jcc(Cond.NE, done);
        a.call(initializer);
        a.bind(done);
    }

    /** Pushes the new object; the {@code dup; invokespecial <init>} that follows is ordinary bytecode. */
    private void newObject(String className) {
        if (pool.get(className).flags().has(AccessFlag.ABSTRACT)) {
            throw error("cannot instantiate abstract class or interface " + className);
        }
        ensureInitialized(className);
        a.lea(RAX, Mem.rip(Compiler.tibSymbol(className)));
        pushLong(RAX);
        a.pushImm(program.layouts().of(className).size());
        ClassPool.ResolvedMethod allocate = pool.resolveMethod(HEAP, "allocateObject", "(JI)Ljava/lang/Object;");
        ensureInitialized(HEAP);
        call(allocate, allocate.method().methodTypeSymbol(), 3);
    }

    /** An exact TIB match is decided inline; anything else asks duke.rt.Types. */
    private void typeCheck(Opcode op, String tib) {
        ensureInitialized(TYPES);
        if (op == Opcode.INSTANCEOF) {
            a.lea(RCX, Mem.rip(tib));
            pushLong(RCX);
            ClassPool.ResolvedMethod m = pool.resolveMethod(TYPES, "instanceOf", "(Ljava/lang/Object;J)Z");
            call(m, m.method().methodTypeSymbol(), 3);
            return;
        }
        X64.Label done = new X64.Label();
        a.load(8, false, RAX, Mem.at(RSP));
        a.test(true, RAX, RAX);
        a.jcc(Cond.E, done);
        a.load(8, false, RAX, Mem.at(RAX));
        a.lea(RCX, Mem.rip(tib));
        a.alu(Alu.CMP, true, RAX, RCX);
        a.jcc(Cond.E, done);
        a.push(Mem.at(RSP));
        pushLong(RCX);
        ClassPool.ResolvedMethod m = pool.resolveMethod(TYPES, "checkCast", "(Ljava/lang/Object;J)V");
        call(m, m.method().methodTypeSymbol(), 3);
        a.bind(done);
    }

    /**
     * Array store check with the stack as {@code array, index, value}. Null values, exact element
     * matches and Object[] targets pass inline. A null array falls through to the NPE check after.
     */
    private void arrayStoreCheck() {
        X64.Label ok = new X64.Label();
        a.load(8, false, RDX, Mem.at(RSP));
        a.test(true, RDX, RDX);
        a.jcc(Cond.E, ok);
        a.load(8, false, RAX, Mem.at(RSP, 16));
        a.test(true, RAX, RAX);
        a.jcc(Cond.E, ok);
        a.load(8, false, RAX, Mem.at(RAX));
        a.load(8, false, RAX, Mem.at(RAX, Layouts.TIB_ELEMENT));
        a.load(8, false, RDX, Mem.at(RDX));
        a.alu(Alu.CMP, true, RAX, RDX);
        a.jcc(Cond.E, ok);
        a.lea(RDX, Mem.rip(program.requireTib(ClassPool.OBJECT)));
        a.alu(Alu.CMP, true, RAX, RDX);
        a.jcc(Cond.E, ok);
        a.push(Mem.at(RSP, 16));
        a.push(Mem.at(RSP, 8));
        ensureInitialized(TYPES);
        ClassPool.ResolvedMethod m = pool.resolveMethod(TYPES, "checkArrayStore", "(Ljava/lang/Object;Ljava/lang/Object;)V");
        call(m, m.method().methodTypeSymbol(), 2);
        a.bind(ok);
    }

    /** Class entries name arrays by descriptor and classes by internal name. */
    private static String descriptorOf(String classEntryName) {
        return classEntryName.startsWith("[") ? classEntryName : "L" + classEntryName + ";";
    }

    private void newArray(String type) {
        a.pop(RCX);
        a.lea(RAX, Mem.rip(program.requireArrayTib(type)));
        pushLong(RAX);
        a.push(RCX);
        a.pushImm(Layouts.width(type.substring(1)));
        ClassPool.ResolvedMethod allocate = pool.resolveMethod(HEAP, "allocateArray", "(JII)Ljava/lang/Object;");
        ensureInitialized(HEAP);
        call(allocate, allocate.method().methodTypeSymbol(), 4);
    }

    /** The dimension counts stay on the operand stack; the runtime reads them in place through rsp. */
    private void newMultiArray(String type, int dimensions) {
        a.mov(RAX, RSP);
        a.lea(RCX, Mem.rip(program.requireMultiArrayDescriptor(type, dimensions)));
        pushLong(RCX);
        pushLong(RAX);
        ClassPool.ResolvedMethod allocate = pool.resolveMethod(HEAP, "allocateMultiArray", "(JJ)Ljava/lang/Object;");
        ensureInitialized(HEAP);
        a.call(program.requireMethod(allocate));
        a.aluImm(Alu.ADD, true, RSP, 8 * (4 + dimensions));
        a.push(RAX);
    }

    /** Lambdas become a static call to a factory on a class LambdaCompiler generates now. */
    private void invokeDynamic(InvokeDynamicInstruction indy) {
        if (indy.bootstrapMethod().owner().displayName().equals("StringConcatFactory")) {
            throw error("string concatenation compiled to invokedynamic; compile the kernel with javac -XDstringConcat=inline");
        }
        if (!LambdaCompiler.isLambda(indy)) {
            throw error("unsupported invokedynamic bootstrap " + indy.bootstrapMethod().owner().displayName());
        }
        ClassPool.ResolvedMethod factory;
        try {
            factory = program.lambdas().proxyFor(indy, method.ownerName());
        } catch (CompileException e) {
            throw error(e.getMessage());
        }
        ensureInitialized(factory.ownerName());
        call(factory, indy.typeSymbol(), argSlots(indy.typeSymbol()));
    }

    /** Receiver's TIB, then its itable, then the selector's slot. */
    private void interfaceCall(String iface, String name, String descriptor, MethodTypeDesc type) {
        int selector = program.requireInterfaceCall(iface, name, descriptor);
        int args = argSlots(type);
        a.load(8, false, RAX, Mem.at(RSP, 8 * args));
        nullCheck(RAX);
        a.load(8, false, RAX, Mem.at(RAX));
        a.load(8, false, RAX, Mem.at(RAX, Layouts.TIB_ITABLE));
        a.call(Mem.at(RAX, 8 * selector));
        afterCall(type, args + 1);
    }

    private void call(ClassPool.ResolvedMethod m, MethodTypeDesc type, int slots) {
        if (m.is(AccessFlag.NATIVE)) {
            throw error("call to native method " + m.ownerName() + "." + m.name() + "; only " + MAGIC + " intrinsics may be native");
        }
        a.call(program.requireMethod(m));
        afterCall(type, slots);
    }

    /** Pops the arguments and pushes the result. */
    private void afterCall(MethodTypeDesc type, int slots) {
        if (slots > 0) {
            a.aluImm(Alu.ADD, true, RSP, 8 * slots);
        }
        switch (TypeKind.from(type.returnType()).slotSize()) {
            case 1 -> a.push(RAX);
            case 2 -> pushLong(RAX);
            default -> { }
        }
    }

    private void magic(String name) {
        switch (name) {
            case "outb" -> portOut(1);
            case "outw" -> portOut(2);
            case "outl" -> portOut(4);
            case "inb" -> portIn(1);
            case "inw" -> portIn(2);
            case "inl" -> portIn(4);
            case "peekByte" -> peek(1);
            case "peekShort" -> peek(2);
            case "peekInt" -> peek(4);
            case "peekLong" -> peek(8);
            case "pokeByte" -> poke(1);
            case "pokeShort" -> poke(2);
            case "pokeInt" -> poke(4);
            case "pokeLong" -> poke(8);
            case "copyMemory" -> copyMemory();
            case "addressOf" -> {
                a.pop(RAX);
                pushLong(RAX);
            }
            case "toObject" -> {
                popLong(RAX);
                a.push(RAX);
            }
            case "heapArenaStart" -> {
                a.lea(RAX, Mem.rip(Compiler.HEAP_ARENA));
                pushLong(RAX);
            }
            case "heapArenaEnd" -> {
                a.lea(RAX, Mem.rip(Compiler.HEAP_ARENA, Compiler.HEAP_ARENA_SIZE));
                pushLong(RAX);
            }
            case "interruptStubs" -> {
                ensureInitialized(Compiler.INTERRUPTS);
                a.lea(RAX, Mem.rip(program.requireInterruptStubs()));
                pushLong(RAX);
            }
            case "loadIdt" -> {
                popLong(RAX);
                a.lidt(Mem.at(RAX));
            }
            case "loadGdt" -> {
                popLong(RAX);
                a.lgdt(Mem.at(RAX));
            }
            case "readCr0", "readCr2", "readCr3", "readCr4" -> {
                a.readCr(name.charAt(6) - '0', RAX);
                pushLong(RAX);
            }
            case "writeCr0", "writeCr3", "writeCr4" -> {
                popLong(RAX);
                a.writeCr(name.charAt(7) - '0', RAX);
            }
            case "invalidatePage" -> {
                popLong(RAX);
                a.invlpg(Mem.at(RAX));
            }
            case "readMsr" -> {
                a.pop(RCX);
                a.rdmsr();
                combineEdxEax();
            }
            case "writeMsr" -> {
                popLong(RAX);
                a.pop(RCX);
                a.mov(RDX, RAX);
                a.push(RCX);
                a.movImm32(RCX, 32);
                a.shiftCl(Shift.SHR, true, RDX);
                a.pop(RCX);
                a.wrmsr();
            }
            case "readTimestamp" -> {
                a.rdtsc();
                combineEdxEax();
            }
            case "flags" -> {
                a.pushfq();
                a.push(RAX);
            }
            case "cpuid" -> {
                popLong(RSI);
                a.pop(RCX);
                a.pop(RAX);
                a.cpuid();
                a.store(4, Mem.at(RSI), RAX);
                a.store(4, Mem.at(RSI, 4), Reg.RBX);
                a.store(4, Mem.at(RSI, 8), RCX);
                a.store(4, Mem.at(RSI, 12), RDX);
            }
            case "breakpoint" -> a.int3();
            case "resetStackLimit" -> {
                a.lea(RAX, Mem.rip("boot.stack", Compiler.STACK_RESERVE));
                a.store(8, Mem.rip(Compiler.STACK_LIMIT), RAX);
            }
            case "resumeAt" -> {
                // resumeAt(handler, rsp, rbp, exception): the JVM's handler entry state is an
                // operand stack holding just the exception, on top of the frame's locals.
                a.pop(RDX);
                popLong(Reg.R8);
                popLong(Reg.R9);
                popLong(RAX);
                a.mov(RBP, Reg.R8);
                a.mov(RSP, Reg.R9);
                a.push(RDX);
                a.jmp(RAX);
            }
            case "framePointer" -> {
                a.push(RBP);
                a.push(RBP);
            }
            case "methodTable" -> {
                a.lea(RAX, Mem.rip(Compiler.METHOD_TABLE));
                pushLong(RAX);
            }
            case "halt" -> a.hlt();
            case "disableInterrupts" -> a.cli();
            case "enableInterrupts" -> a.sti();
            case "pause" -> a.pause();
            default -> throw error("unknown intrinsic " + MAGIC + "." + name);
        }
    }

    /** rep movsb, run backwards when the destination overlaps the end of the source. */
    private void copyMemory() {
        popLong(RCX);
        popLong(RSI);
        popLong(Reg.RDI);
        X64.Label forward = new X64.Label();
        X64.Label done = new X64.Label();
        a.alu(Alu.CMP, true, Reg.RDI, RSI);
        a.jcc(Cond.BE, forward);
        a.lea(RSI, Mem.at(RSI, RCX, 1, -1));
        a.lea(Reg.RDI, Mem.at(Reg.RDI, RCX, 1, -1));
        a.std();
        a.repMovsb();
        a.cld();
        a.jmp(done);
        a.bind(forward);
        a.repMovsb();
        a.bind(done);
    }

    /** Pushes edx:eax as one long. */
    private void combineEdxEax() {
        a.mov32(RAX, RAX);
        a.movImm32(RCX, 32);
        a.shiftCl(Shift.SHL, true, RDX);
        a.alu(Alu.OR, true, RAX, RDX);
        pushLong(RAX);
    }

    private void portOut(int width) {
        a.pop(RAX);
        a.pop(RDX);
        a.out(width);
    }

    private void portIn(int width) {
        a.pop(RDX);
        a.in(width);
        if (width == 1) {
            a.movzx8(RAX, RAX);
        } else if (width == 2) {
            a.movzx16(RAX, RAX);
        }
        a.push(RAX);
    }

    private void peek(int width) {
        popLong(RAX);
        a.load(width, true, RAX, Mem.at(RAX));
        pushValue(RAX, width == 8);
    }

    private void poke(int width) {
        popValue(RDX, width == 8);
        popLong(RAX);
        a.store(width, Mem.at(RAX), RDX);
    }

    private static int arrayWidth(TypeKind kind) {
        return switch (kind) {
            case BYTE, BOOLEAN -> 1;
            case CHAR, SHORT -> 2;
            case INT -> 4;
            case LONG, REFERENCE -> 8;
            default -> 0;
        };
    }

    /** Leaves the array in rax and the bounds-checked, zero-extended index in rcx. */
    private void checkedArrayAccess() {
        a.pop(RCX);
        a.pop(RAX);
        nullCheck(RAX);
        X64.Label inBounds = new X64.Label();
        a.alu(Alu.CMP, false, Mem.at(RAX, Layouts.ARRAY_LENGTH_OFFSET), RCX);
        a.jcc(Cond.A, inBounds);
        a.push(RCX);
        a.push(Mem.at(RAX, Layouts.ARRAY_LENGTH_OFFSET));
        a.call(program.requireMethod(RUNTIME, "arrayIndexOutOfBounds", "(II)V"));
        a.bind(inBounds);
        a.mov32(RCX, RCX);
    }

    private void arrayLoad(TypeKind kind) {
        int width = arrayWidth(kind);
        if (width == 0) {
            throw error("unsupported array element type " + kind);
        }
        checkedArrayAccess();
        a.load(width, kind == TypeKind.BYTE || kind == TypeKind.SHORT, RAX,
                Mem.at(RAX, RCX, width, Layouts.ARRAY_DATA_OFFSET));
        pushValue(RAX, kind == TypeKind.LONG);
    }

    private void arrayStore(TypeKind kind) {
        int width = arrayWidth(kind);
        if (width == 0) {
            throw error("unsupported array element type " + kind);
        }
        if (kind == TypeKind.REFERENCE) {
            arrayStoreCheck();
        }
        popValue(RDX, kind == TypeKind.LONG);
        checkedArrayAccess();
        a.store(width, Mem.at(RAX, RCX, width, Layouts.ARRAY_DATA_OFFSET), RDX);
    }

    /**
     * Every frame checks the stack limit once it has its locals. Crossing it calls the compiler's
     * overflow stub, which lowers the limit into the reserve and throws StackOverflowError.
     */
    private void stackCheck() {
        program.requireStackOverflowStub();
        X64.Label ok = new X64.Label();
        a.alu(Alu.CMP, true, Mem.rip(Compiler.STACK_LIMIT), RSP);
        a.jcc(Cond.BE, ok);
        a.call(Compiler.STACK_OVERFLOW);
        a.bind(ok);
    }

    /**
     * Runtime checks call their throwing handler inline rather than from an out-of-line stub, so
     * the return address sits inside the try range and the source line of the check.
     */
    private void nullCheck(Reg r) {
        X64.Label ok = new X64.Label();
        a.test(true, r, r);
        a.jcc(Cond.NE, ok);
        a.call(program.requireMethod(RUNTIME, "nullPointer", "()V"));
        a.bind(ok);
    }

    /**
     * Rows of (start, end, handler, catch type TIB) over this method's code, in the order the class
     * file lists them, which is the order the JVM tries them. Offsets are from the method start.
     */
    private void recordExceptionTable(String symbol, int start, int extraLocals) {
        List<Compiler.Handler> handlers = new ArrayList<>();
        for (ExceptionCatch c : catches) {
            String catchType = c.catchType().map(t -> program.requireTib(t.asInternalName())).orElse(null);
            handlers.add(new Compiler.Handler(label(c.tryStart()).position() - start, label(c.tryEnd()).position() - start,
                    label(c.handler()).position() - start, catchType));
        }
        if (!handlers.isEmpty()) {
            program.recordExceptionTable(symbol, 8 * Math.max(extraLocals, 0), handlers);
        }
    }

    private void checkLabelsBound() {
        for (X64.Label l : labels.values()) {
            if (!l.isBound()) {
                throw error("internal: branch target never bound");
            }
        }
    }
}
