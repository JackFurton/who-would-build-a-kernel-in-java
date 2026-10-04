package duke.compiler;

import java.lang.classfile.Attributes;
import java.lang.classfile.CodeElement;
import java.lang.classfile.Instruction;
import java.lang.classfile.Label;
import java.lang.classfile.Opcode;
import java.lang.classfile.TypeKind;
import java.lang.classfile.attribute.CodeAttribute;
import java.lang.classfile.attribute.StackMapFrameInfo;
import java.lang.classfile.instruction.ArrayLoadInstruction;
import java.lang.classfile.instruction.ArrayStoreInstruction;
import java.lang.classfile.instruction.BranchInstruction;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.ConvertInstruction;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.IncrementInstruction;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LabelTarget;
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
import java.lang.classfile.instruction.TableSwitchInstruction;
import java.lang.classfile.instruction.ThrowInstruction;
import java.lang.classfile.instruction.TypeCheckInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which JVM slots hold references before each instruction, for the garbage collector's stack
 * maps. This is the JVM's type-checking verifier pass reduced to two kinds: a linear walk that
 * applies each instruction's effect, resetting to the class file's StackMapTable frame at every
 * branch target (javac emits one wherever control flow merges). Slots follow the code generator's
 * model exactly: one per JVM slot, two for long and double, the value in the lower one.
 */
final class FrameTypes {

    static final byte TOP = 0;
    static final byte VALUE = 1;
    static final byte REF = 2;

    /** Locals and operand stack (bottom first) before one instruction. */
    record State(byte[] locals, byte[] stack) {}

    private final Map<Label, StackMapFrameInfo> frames = new IdentityHashMap<>();
    private final int maxLocals;
    private byte[] locals;
    private byte[] stack;
    private int depth;
    private boolean reachable = true;

    private FrameTypes(CodeAttribute code) {
        this.maxLocals = code.maxLocals();
        code.findAttribute(Attributes.stackMapTable())
                .ifPresent(t -> t.entries().forEach(f -> frames.put(f.target(), f)));
    }

    /** States before each element, indexed like {@code elements}; null where code is unreachable. */
    static State[] analyze(CodeAttribute code, List<CodeElement> elements, MethodTypeDesc type, boolean isStatic) {
        FrameTypes f = new FrameTypes(code);
        f.locals = new byte[code.maxLocals()];
        f.stack = new byte[code.maxStack() + 2];
        int slot = 0;
        if (!isStatic) {
            f.locals[slot++] = REF;
        }
        for (ClassDesc p : type.parameterList()) {
            TypeKind kind = TypeKind.from(p);
            f.locals[slot] = kind == TypeKind.REFERENCE ? REF : VALUE;
            if (kind.slotSize() == 2) {
                f.locals[slot + 1] = VALUE;
            }
            slot += kind.slotSize();
        }

        State[] states = new State[elements.size()];
        for (int i = 0; i < elements.size(); i++) {
            CodeElement e = elements.get(i);
            if (e instanceof LabelTarget t && f.frames.containsKey(t.label())) {
                f.reset(f.frames.get(t.label()));
            }
            if (e instanceof Instruction ins) {
                if (f.reachable) {
                    states[i] = new State(f.locals.clone(), Arrays.copyOf(f.stack, f.depth));
                    f.apply(ins);
                }
            }
        }
        return states;
    }

    private void reset(StackMapFrameInfo frame) {
        Arrays.fill(locals, TOP);
        int slot = 0;
        for (StackMapFrameInfo.VerificationTypeInfo v : frame.locals()) {
            slot = put(locals, slot, v);
        }
        depth = 0;
        for (StackMapFrameInfo.VerificationTypeInfo v : frame.stack()) {
            depth = put(stack, depth, v);
        }
        reachable = true;
    }

    private static int put(byte[] slots, int at, StackMapFrameInfo.VerificationTypeInfo v) {
        switch (v.tag()) {
            case StackMapFrameInfo.VerificationTypeInfo.ITEM_LONG, StackMapFrameInfo.VerificationTypeInfo.ITEM_DOUBLE -> {
                slots[at] = VALUE;
                slots[at + 1] = VALUE;
                return at + 2;
            }
            case StackMapFrameInfo.VerificationTypeInfo.ITEM_TOP -> slots[at] = TOP;
            case StackMapFrameInfo.VerificationTypeInfo.ITEM_INTEGER, StackMapFrameInfo.VerificationTypeInfo.ITEM_FLOAT -> slots[at] = VALUE;
            // Null, objects, and uninitialized objects after `new` or `this` in a constructor.
            default -> slots[at] = REF;
        }
        return at + 1;
    }

    private void push(byte kind) {
        stack[depth++] = kind;
    }

    private void push(TypeKind kind) {
        if (kind == TypeKind.VOID) {
            return;
        }
        push(kind == TypeKind.REFERENCE ? REF : VALUE);
        if (kind.slotSize() == 2) {
            push(VALUE);
        }
    }

    private void pop(int slots) {
        depth -= slots;
        if (depth < 0) {
            throw new IllegalStateException("operand stack underflow");
        }
    }

    private static int slots(MethodTypeDesc type) {
        int n = 0;
        for (ClassDesc p : type.parameterList()) {
            n += TypeKind.from(p).slotSize();
        }
        return n;
    }

    private void apply(Instruction i) {
        switch (i) {
            case LoadInstruction l -> {
                push(l.typeKind() == TypeKind.REFERENCE ? REF : VALUE);
                if (l.typeKind().slotSize() == 2) {
                    push(VALUE);
                }
            }
            case StoreInstruction s -> {
                pop(s.typeKind().slotSize());
                locals[s.slot()] = s.typeKind() == TypeKind.REFERENCE ? REF : VALUE;
                if (s.typeKind().slotSize() == 2) {
                    locals[s.slot() + 1] = VALUE;
                }
            }
            case IncrementInstruction inc -> { }
            case ConstantInstruction c -> {
                if (c.opcode() == Opcode.ACONST_NULL) {
                    push(REF);
                } else {
                    push(c.typeKind());
                }
            }
            case OperatorInstruction op -> operator(op);
            case ConvertInstruction c -> {
                pop(c.fromType().slotSize());
                push(c.toType());
            }
            case BranchInstruction b -> {
                switch (b.opcode()) {
                    case GOTO, GOTO_W -> reachable = false;
                    case IFEQ, IFNE, IFLT, IFGE, IFGT, IFLE, IFNULL, IFNONNULL -> pop(1);
                    default -> pop(2);
                }
            }
            case TableSwitchInstruction t -> {
                pop(1);
                reachable = false;
            }
            case LookupSwitchInstruction l -> {
                pop(1);
                reachable = false;
            }
            case StackInstruction s -> shuffle(s.opcode());
            case FieldInstruction f -> {
                TypeKind kind = TypeKind.from(f.typeSymbol());
                switch (f.opcode()) {
                    case GETSTATIC -> push(kind);
                    case PUTSTATIC -> pop(kind.slotSize());
                    case GETFIELD -> {
                        pop(1);
                        push(kind);
                    }
                    case PUTFIELD -> pop(1 + kind.slotSize());
                    default -> throw new IllegalStateException(f.opcode().name());
                }
            }
            case InvokeInstruction inv -> {
                pop(slots(inv.typeSymbol()) + (inv.opcode() == Opcode.INVOKESTATIC ? 0 : 1));
                push(TypeKind.from(inv.typeSymbol().returnType()));
            }
            case InvokeDynamicInstruction indy -> {
                pop(slots(indy.typeSymbol()));
                push(TypeKind.from(indy.typeSymbol().returnType()));
            }
            case NewObjectInstruction n -> push(REF);
            case NewPrimitiveArrayInstruction n -> {
                pop(1);
                push(REF);
            }
            case NewReferenceArrayInstruction n -> {
                pop(1);
                push(REF);
            }
            case NewMultiArrayInstruction n -> {
                pop(n.dimensions());
                push(REF);
            }
            case ArrayLoadInstruction l -> {
                pop(2);
                push(l.typeKind());
            }
            case ArrayStoreInstruction s -> pop(2 + s.typeKind().slotSize());
            case TypeCheckInstruction t -> {
                pop(1);
                push(t.opcode() == Opcode.CHECKCAST ? REF : VALUE);
            }
            case ThrowInstruction t -> reachable = false;
            case ReturnInstruction r -> reachable = false;
            case NopInstruction n -> { }
            // Anything else is rejected by MethodCompiler with a proper error.
            default -> { }
        }
    }

    private void operator(OperatorInstruction op) {
        switch (op.opcode()) {
            case ARRAYLENGTH -> {
                pop(1);
                push(VALUE);
            }
            case INEG, FNEG -> { }
            case LNEG, DNEG -> { }
            case LCMP, DCMPL, DCMPG -> {
                pop(4);
                push(VALUE);
            }
            case FCMPL, FCMPG -> {
                pop(2);
                push(VALUE);
            }
            case LSHL, LSHR, LUSHR -> pop(1);
            default -> {
                // Binary operators: both operands and the result share the instruction's type.
                int size = op.typeKind().slotSize();
                pop(size);
            }
        }
    }

    /** Mirrors MethodCompiler.stack exactly, on slot kinds instead of machine words. */
    private void shuffle(Opcode op) {
        byte v1 = depth > 0 ? stack[depth - 1] : TOP;
        byte v2 = depth > 1 ? stack[depth - 2] : TOP;
        byte v3 = depth > 2 ? stack[depth - 3] : TOP;
        byte v4 = depth > 3 ? stack[depth - 4] : TOP;
        switch (op) {
            case POP -> pop(1);
            case POP2 -> pop(2);
            case DUP -> push(v1);
            case DUP2 -> {
                push(v2);
                push(v1);
            }
            case SWAP -> {
                stack[depth - 1] = v2;
                stack[depth - 2] = v1;
            }
            case DUP_X1 -> set(2, v1, v2, v1);
            case DUP_X2 -> set(3, v1, v3, v2, v1);
            case DUP2_X1 -> set(3, v2, v1, v3, v2, v1);
            case DUP2_X2 -> set(4, v2, v1, v4, v3, v2, v1);
            default -> throw new IllegalStateException(op.name());
        }
    }

    /** Replaces the top {@code popped} slots with {@code pushed}, bottom first. */
    private void set(int popped, byte... pushed) {
        depth -= popped;
        for (byte b : pushed) {
            push(b);
        }
    }

    int maxLocals() {
        return maxLocals;
    }
}
