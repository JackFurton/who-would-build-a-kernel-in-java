package java.lang;

public final class StackTraceElement {

    private final String declaringClass;
    private final String methodName;
    private final String fileName;
    private final int lineNumber;

    public StackTraceElement(String declaringClass, String methodName, String fileName, int lineNumber) {
        this.declaringClass = declaringClass;
        this.methodName = methodName;
        this.fileName = fileName;
        this.lineNumber = lineNumber;
    }

    public String getClassName() {
        return declaringClass;
    }

    public String getMethodName() {
        return methodName;
    }

    public String getFileName() {
        return fileName;
    }

    public int getLineNumber() {
        return lineNumber;
    }

    /** Same shape as the JDK's for classes outside named modules: {@code pkg.Cls.method(File.java:12)}. */
    @Override
    public String toString() {
        String location = fileName == null ? "Unknown Source" : lineNumber > 0 ? fileName + ":" + lineNumber : fileName;
        String method = declaringClass.isEmpty() ? methodName : declaringClass + "." + methodName;
        return method + "(" + location + ")";
    }
}
