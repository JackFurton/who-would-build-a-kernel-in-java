package java.lang.invoke;

/**
 * The bootstrap javac names for lambdas and method references. dukec never calls it: it reads the
 * bootstrap arguments and generates the implementing class itself (LambdaCompiler).
 */
public final class LambdaMetafactory {

    private LambdaMetafactory() {
    }

    public static CallSite metafactory(MethodHandles.Lookup caller, String interfaceMethodName, MethodType factoryType,
            MethodType interfaceMethodType, MethodHandle implementation, MethodType dynamicMethodType) {
        return null;
    }

    public static CallSite altMetafactory(MethodHandles.Lookup caller, String interfaceMethodName,
            MethodType factoryType, Object... args) {
        return null;
    }
}
