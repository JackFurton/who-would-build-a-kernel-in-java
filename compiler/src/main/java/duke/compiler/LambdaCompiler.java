package duke.compiler;

import static java.lang.constant.ConstantDescs.CD_Object;
import static java.lang.constant.ConstantDescs.CD_void;

import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.TypeKind;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDesc;
import java.lang.constant.DirectMethodHandleDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.util.List;
import java.util.Map;

/**
 * Does LambdaMetafactory's job at build time. For each lambda or method reference call site it
 * generates a class implementing the functional interface, with one field per captured value and a
 * static {@code create} factory, and adds it to the class pool. The call site then compiles as a
 * plain static call to the factory.
 */
final class LambdaCompiler {

    private static final ClassDesc METAFACTORY = ClassDesc.of("java.lang.invoke.LambdaMetafactory");
    private static final String FACTORY = "create";

    private static final Map<String, ClassDesc> WRAPPERS = Map.of(
            "I", ClassDesc.of("java.lang.Integer"),
            "J", ClassDesc.of("java.lang.Long"),
            "Z", ClassDesc.of("java.lang.Boolean"),
            "C", ClassDesc.of("java.lang.Character"),
            "B", ClassDesc.of("java.lang.Byte"),
            "S", ClassDesc.of("java.lang.Short"));

    private final ClassPool pool;
    private int proxies;

    LambdaCompiler(ClassPool pool) {
        this.pool = pool;
    }

    static boolean isLambda(InvokeDynamicInstruction indy) {
        return indy.bootstrapMethod().owner().equals(METAFACTORY);
    }

    /** Generates the proxy class for {@code indy} and returns its factory method. */
    ClassPool.ResolvedMethod proxyFor(InvokeDynamicInstruction indy, String callerClass) {
        if (!indy.bootstrapMethod().methodName().equals("metafactory")) {
            throw new CompileException("LambdaMetafactory." + indy.bootstrapMethod().methodName()
                    + " (serializable lambdas, marker interfaces) is not supported");
        }
        List<ConstantDesc> args = indy.bootstrapArgs();
        MethodTypeDesc interfaceType = (MethodTypeDesc) args.get(0);
        DirectMethodHandleDesc implementation = (DirectMethodHandleDesc) args.get(1);
        MethodTypeDesc instantiatedType = (MethodTypeDesc) args.get(2);
        MethodTypeDesc factoryType = indy.typeSymbol();
        String methodName = indy.name().stringValue();

        ClassDesc proxy = ClassDesc.ofInternalName(callerClass + "$$Lambda$" + proxies++);
        List<ClassDesc> captures = factoryType.parameterList();
        ClassDesc iface = factoryType.returnType();
        boolean singleton = captures.isEmpty();

        byte[] bytes = ClassFile.of(ClassFile.StackMapsOption.DROP_STACK_MAPS).build(proxy, cb -> {
            cb.withFlags(AccessFlag.FINAL, AccessFlag.SYNTHETIC, AccessFlag.SUPER);
            cb.withSuperclass(CD_Object);
            cb.withInterfaceSymbols(iface);
            for (int i = 0; i < captures.size(); i++) {
                cb.withField("capture" + i, captures.get(i), ClassFile.ACC_PRIVATE | ClassFile.ACC_FINAL);
            }
            if (singleton) {
                cb.withField("INSTANCE", proxy, ClassFile.ACC_PRIVATE | ClassFile.ACC_STATIC | ClassFile.ACC_FINAL);
                cb.withMethodBody("<clinit>", MethodTypeDesc.of(CD_void), ClassFile.ACC_STATIC, code -> code
                        .new_(proxy).dup().invokespecial(proxy, "<init>", MethodTypeDesc.of(CD_void))
                        .putstatic(proxy, "INSTANCE", proxy).return_());
            }

            MethodTypeDesc constructorType = MethodTypeDesc.of(CD_void, captures);
            cb.withMethodBody("<init>", constructorType, ClassFile.ACC_PRIVATE, code -> {
                code.aload(0).invokespecial(CD_Object, "<init>", MethodTypeDesc.of(CD_void));
                int slot = 1;
                for (int i = 0; i < captures.size(); i++) {
                    code.aload(0).loadLocal(TypeKind.from(captures.get(i)), slot).putfield(proxy, "capture" + i, captures.get(i));
                    slot += TypeKind.from(captures.get(i)).slotSize();
                }
                code.return_();
            });

            cb.withMethodBody(FACTORY, factoryType, ClassFile.ACC_STATIC, code -> {
                if (singleton) {
                    code.getstatic(proxy, "INSTANCE", proxy).areturn();
                    return;
                }
                code.new_(proxy).dup();
                int slot = 0;
                for (ClassDesc c : captures) {
                    code.loadLocal(TypeKind.from(c), slot);
                    slot += TypeKind.from(c).slotSize();
                }
                code.invokespecial(proxy, "<init>", constructorType).areturn();
            });

            cb.withMethodBody(methodName, interfaceType, ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL,
                    code -> forward(code, proxy, captures, interfaceType, instantiatedType, implementation));
        });
        pool.add(ClassFile.of().parse(bytes));
        return pool.resolveMethod(proxy.descriptorString().substring(1, proxy.descriptorString().length() - 1),
                FACTORY, factoryType.descriptorString());
    }

    /**
     * The interface method body: captured values then interface arguments, each adapted to the
     * implementation's parameter types, then the call, then the result adapted back.
     */
    private static void forward(CodeBuilder code, ClassDesc proxy, List<ClassDesc> captures, MethodTypeDesc interfaceType,
            MethodTypeDesc instantiatedType, DirectMethodHandleDesc implementation) {
        boolean constructor = implementation.kind() == DirectMethodHandleDesc.Kind.CONSTRUCTOR;
        List<ClassDesc> implParams = implementation.invocationType().parameterList();
        if (constructor) {
            code.new_(implementation.owner()).dup();
        }
        int next = 0;
        for (int i = 0; i < captures.size(); i++) {
            code.aload(0).getfield(proxy, "capture" + i, captures.get(i));
            adapt(code, captures.get(i), implParams.get(next++));
        }
        int slot = 1;
        for (int i = 0; i < interfaceType.parameterCount(); i++) {
            ClassDesc declared = interfaceType.parameterType(i);
            code.loadLocal(TypeKind.from(declared), slot);
            slot += TypeKind.from(declared).slotSize();
            ClassDesc instantiated = instantiatedType.parameterType(i);
            adapt(code, declared, instantiated);
            adapt(code, instantiated, implParams.get(next++));
        }

        ClassDesc owner = implementation.owner();
        String name = implementation.methodName();
        MethodTypeDesc type = MethodTypeDesc.ofDescriptor(implementation.lookupDescriptor());
        ClassDesc produced;
        switch (implementation.kind()) {
            case STATIC -> code.invokestatic(owner, name, type);
            case INTERFACE_STATIC -> code.invokestatic(owner, name, type, true);
            case VIRTUAL -> code.invokevirtual(owner, name, type);
            case INTERFACE_VIRTUAL -> code.invokeinterface(owner, name, type);
            case SPECIAL -> code.invokespecial(owner, name, type);
            case INTERFACE_SPECIAL -> code.invokespecial(owner, name, type, true);
            case CONSTRUCTOR -> code.invokespecial(owner, "<init>", type);
            default -> throw new CompileException("method handle kind " + implementation.kind() + " in a lambda");
        }
        produced = constructor ? owner : type.returnType();

        ClassDesc returned = interfaceType.returnType();
        if (returned.equals(CD_void)) {
            if (!produced.equals(CD_void)) {
                if (TypeKind.from(produced).slotSize() == 2) {
                    code.pop2();
                } else {
                    code.pop();
                }
            }
            code.return_();
            return;
        }
        adapt(code, produced, instantiatedType.returnType());
        adapt(code, instantiatedType.returnType(), returned);
        code.return_(TypeKind.from(returned));
    }

    /** Casts, boxes, unboxes or widens the value on top of the stack from {@code from} to {@code to}. */
    private static void adapt(CodeBuilder code, ClassDesc from, ClassDesc to) {
        if (from.equals(to)) {
            return;
        }
        if (!from.isPrimitive() && !to.isPrimitive()) {
            if (!to.equals(CD_Object)) {
                code.checkcast(to);
            }
            return;
        }
        if (from.isPrimitive() && to.isPrimitive()) {
            code.conversion(TypeKind.from(from), TypeKind.from(to));
            return;
        }
        if (from.isPrimitive()) {
            ClassDesc wrapper = wrapper(from);
            code.invokestatic(wrapper, "valueOf", MethodTypeDesc.of(wrapper, from));
            if (!to.equals(CD_Object) && !to.equals(wrapper)) {
                code.checkcast(to);
            }
            return;
        }
        // Reference to primitive: unbox through the source's own wrapper when it names one.
        ClassDesc wrapper = WRAPPERS.containsValue(from) ? from : wrapper(to);
        ClassDesc primitive = primitiveOf(wrapper);
        if (!from.equals(wrapper)) {
            code.checkcast(wrapper);
        }
        code.invokevirtual(wrapper, TypeKind.from(primitive).upperBound().displayName() + "Value",
                MethodTypeDesc.of(primitive));
        if (!primitive.equals(to)) {
            code.conversion(TypeKind.from(primitive), TypeKind.from(to));
        }
    }

    private static ClassDesc wrapper(ClassDesc primitive) {
        ClassDesc wrapper = WRAPPERS.get(primitive.descriptorString());
        if (wrapper == null) {
            throw new CompileException("cannot box " + primitive.displayName() + " in a lambda yet");
        }
        return wrapper;
    }

    private static ClassDesc primitiveOf(ClassDesc wrapper) {
        for (Map.Entry<String, ClassDesc> e : WRAPPERS.entrySet()) {
            if (e.getValue().equals(wrapper)) {
                return ClassDesc.ofDescriptor(e.getKey());
            }
        }
        throw new IllegalArgumentException(wrapper.displayName());
    }
}
