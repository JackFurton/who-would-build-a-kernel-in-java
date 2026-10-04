package duke.compiler;

import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.Instruction;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.TypeKind;
import java.lang.classfile.instruction.ArrayLoadInstruction;
import java.lang.classfile.instruction.ArrayStoreInstruction;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.LabelTarget;
import java.lang.classfile.instruction.LineNumber;
import java.lang.classfile.instruction.LocalVariable;
import java.lang.classfile.instruction.NewPrimitiveArrayInstruction;
import java.lang.classfile.instruction.NewReferenceArrayInstruction;
import java.lang.classfile.instruction.OperatorInstruction;
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.classfile.instruction.StackInstruction;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Runs static initializers at build time when they only build constants: straight-line code
 * made of constant pushes, primitive and reference array creation, dup, array loads, stores and
 * lengths, and reads and writes of the class's own static fields. Everything else returns null and the class keeps lazy runtime
 * initialization, so behaviour never changes, only when the work happens. That is unobservable
 * here: such an initializer has no effect beyond its own fields.
 */
final class BuildTimeInit {

    /** A value on the interpreter's stack or in a static field. */
    sealed interface Value permits Prim, Str, Arr, Null {}

    record Prim(long bits) implements Value {}

    record Str(String value) implements Value {}

    /** An array built at compile time; elements are Prim, Str, Arr or Null. */
    static final class Arr implements Value {
        final String type;
        final Value[] elements;

        Arr(String type, int length) {
            this.type = type;
            this.elements = new Value[length];
            Value zero = type.charAt(1) == 'L' || type.charAt(1) == '[' ? Null.NULL : new Prim(0);
            java.util.Arrays.fill(elements, zero);
        }
    }

    enum Null implements Value { NULL }

    private BuildTimeInit() {
    }

    /** The static field values the initializer produces, or null if it can't run at build time. */
    static Map<String, Value> evaluate(ClassModel model) {
        MethodModel clinit = null;
        for (MethodModel m : model.methods()) {
            if (m.methodName().equalsString("<clinit>")) {
                clinit = m;
            }
        }
        if (clinit == null || clinit.code().isEmpty()) {
            return null;
        }
        String self = model.thisClass().asInternalName();
        Deque<Value> stack = new ArrayDeque<>();
        Map<String, Value> statics = new LinkedHashMap<>();
        for (CodeElement e : clinit.code().get()) {
            switch (e) {
                case LineNumber n -> { }
                case LocalVariable v -> { }
                case LabelTarget t -> { }
                case Instruction i -> {
                    if (!step(i, self, stack, statics)) {
                        return null;
                    }
                }
                default -> {
                    return null;
                }
            }
        }
        return statics;
    }

    private static boolean step(Instruction i, String self, Deque<Value> stack, Map<String, Value> statics) {
        switch (i) {
            case ConstantInstruction c -> {
                if (c.opcode() == Opcode.ACONST_NULL) {
                    stack.push(Null.NULL);
                    return true;
                }
                switch (c.constantValue()) {
                    case Integer v -> stack.push(new Prim(v));
                    case Long v -> stack.push(new Prim(v));
                    case String s -> stack.push(new Str(s));
                    default -> {
                        return false;
                    }
                }
                return true;
            }
            case NewPrimitiveArrayInstruction n -> {
                return newArray("[" + n.typeKind().upperBound().descriptorString(), stack);
            }
            case NewReferenceArrayInstruction n -> {
                String component = n.componentType().asInternalName();
                return newArray("[" + (component.startsWith("[") ? component : "L" + component + ";"), stack);
            }
            case StackInstruction s when s.opcode() == Opcode.DUP -> {
                if (stack.isEmpty()) {
                    return false;
                }
                stack.push(stack.peek());
                return true;
            }
            case ArrayStoreInstruction s -> {
                if (s.typeKind() == TypeKind.FLOAT || s.typeKind() == TypeKind.DOUBLE || stack.size() < 3) {
                    return false;
                }
                Value value = stack.pop();
                Value index = stack.pop();
                Value array = stack.pop();
                if (!(array instanceof Arr arr) || !(index instanceof Prim idx)
                        || idx.bits() < 0 || idx.bits() >= arr.elements.length) {
                    return false;
                }
                boolean primitive = s.typeKind() != TypeKind.REFERENCE;
                if (value instanceof Prim != primitive || !storable(arr, value)) {
                    return false;
                }
                // Narrow the way the store instruction would.
                arr.elements[(int) idx.bits()] = switch (s.typeKind()) {
                    case BYTE -> new Prim((byte) ((Prim) value).bits());
                    case CHAR -> new Prim((char) ((Prim) value).bits());
                    case SHORT -> new Prim((short) ((Prim) value).bits());
                    case INT -> new Prim((int) ((Prim) value).bits());
                    default -> value;
                };
                return true;
            }
            case FieldInstruction f when f.opcode() == Opcode.PUTSTATIC && f.owner().asInternalName().equals(self) -> {
                if (stack.isEmpty()) {
                    return false;
                }
                statics.put(f.name().stringValue(), stack.pop());
                return true;
            }
            case FieldInstruction f when f.opcode() == Opcode.GETSTATIC && f.owner().asInternalName().equals(self)
                    && statics.containsKey(f.name().stringValue()) -> {
                stack.push(statics.get(f.name().stringValue()));
                return true;
            }
            case OperatorInstruction o when o.opcode() == Opcode.ARRAYLENGTH -> {
                if (stack.isEmpty() || !(stack.pop() instanceof Arr arr)) {
                    return false;
                }
                stack.push(new Prim(arr.elements.length));
                return true;
            }
            case ArrayLoadInstruction l -> {
                if (stack.size() < 2 || !(stack.pop() instanceof Prim idx) || !(stack.pop() instanceof Arr arr)
                        || idx.bits() < 0 || idx.bits() >= arr.elements.length) {
                    return false;
                }
                stack.push(arr.elements[(int) idx.bits()]);
                return true;
            }
            case ReturnInstruction r -> {
                return r.typeKind() == TypeKind.VOID && stack.isEmpty();
            }
            default -> {
                return false;
            }
        }
    }

    private static boolean newArray(String type, Deque<Value> stack) {
        if (stack.isEmpty() || !(stack.pop() instanceof Prim length) || length.bits() < 0 || length.bits() > 1 << 20
                || type.equals("[F") || type.equals("[D")) {
            return false;
        }
        stack.push(new Arr(type, (int) length.bits()));
        return true;
    }

    /** Only element types we can check without a class hierarchy: exact strings and arrays. */
    private static boolean storable(Arr array, Value value) {
        String element = array.type.substring(1);
        return switch (value) {
            case Prim p -> true;
            case Null n -> true;
            case Str s -> element.equals("Ljava/lang/String;") || element.equals("Ljava/lang/Object;");
            case Arr a -> element.equals(a.type) || element.equals("Ljava/lang/Object;");
        };
    }

    /** Keyed by identity: two equal-looking arrays are still two objects. */
    static Map<Arr, String> symbolsFor(Iterable<Map<String, Value>> classes) {
        Map<Arr, String> symbols = new HashMap<>();
        for (Map<String, Value> statics : classes) {
            for (Value v : statics.values()) {
                assign(v, symbols);
            }
        }
        return symbols;
    }

    private static void assign(Value v, Map<Arr, String> symbols) {
        if (v instanceof Arr a && !symbols.containsKey(a)) {
            symbols.put(a, "image-array:" + symbols.size());
            for (Value e : a.elements) {
                assign(e, symbols);
            }
        }
    }
}
