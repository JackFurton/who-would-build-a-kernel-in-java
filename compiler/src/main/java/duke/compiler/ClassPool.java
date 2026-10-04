package duke.compiler;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodModel;
import java.lang.reflect.AccessFlag;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Every class in the kernel image. The world is closed: anything not here does not exist. */
public final class ClassPool {

    public record ResolvedMethod(ClassModel owner, MethodModel method) {

        public String ownerName() {
            return owner.thisClass().asInternalName();
        }

        public String name() {
            return method.methodName().stringValue();
        }

        public String descriptor() {
            return method.methodType().stringValue();
        }

        public boolean is(AccessFlag flag) {
            return method.flags().has(flag);
        }
    }

    public record ResolvedField(ClassModel owner, FieldModel field) {

        public String ownerName() {
            return owner.thisClass().asInternalName();
        }

        public String name() {
            return field.fieldName().stringValue();
        }

        public String descriptor() {
            return field.fieldType().stringValue();
        }
    }

    public static final String OBJECT = "java/lang/Object";

    private final Map<String, ClassModel> classes = new LinkedHashMap<>();

    public static ClassPool load(Path root) {
        ClassPool pool = new ClassPool();
        try (Stream<Path> files = Files.walk(root)) {
            List<Path> classFiles = files
                    .filter(p -> p.toString().endsWith(".class"))
                    .filter(p -> !p.getFileName().toString().equals("module-info.class"))
                    .sorted()
                    .toList();
            for (Path p : classFiles) {
                pool.add(ClassFile.of().parse(Files.readAllBytes(p)));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return pool;
    }

    public void add(ClassModel model) {
        classes.put(model.thisClass().asInternalName(), model);
    }

    public Collection<ClassModel> all() {
        return classes.values();
    }

    public ClassModel get(String name) {
        ClassModel model = classes.get(name);
        if (model == null) {
            throw new CompileException("class " + name + " is not part of the kernel image");
        }
        return model;
    }

    public String superName(ClassModel model) {
        return model.superclass().map(c -> c.asInternalName()).orElse(null);
    }

    public boolean isSubclass(String sub, String sup) {
        for (String c = sub; c != null; c = superName(get(c))) {
            if (c.equals(sup)) {
                return true;
            }
        }
        return false;
    }

    public MethodModel declaredMethod(ClassModel model, String name, String descriptor) {
        for (MethodModel m : model.methods()) {
            if (m.methodName().equalsString(name) && m.methodType().equalsString(descriptor)) {
                return m;
            }
        }
        return null;
    }

    /** JVMS 5.4.3.3 for classes, minus interfaces: walk up the superclass chain. */
    public ResolvedMethod resolveMethod(String owner, String name, String descriptor) {
        ResolvedMethod m = findMethod(owner, name, descriptor);
        if (m == null) {
            throw new CompileException("no method " + owner + "." + name + descriptor);
        }
        return m;
    }

    public ResolvedMethod findMethod(String owner, String name, String descriptor) {
        for (String c = owner; c != null; ) {
            ClassModel model = get(c);
            MethodModel m = declaredMethod(model, name, descriptor);
            if (m != null) {
                return new ResolvedMethod(model, m);
            }
            c = superName(model);
        }
        return null;
    }

    public ResolvedField resolveField(String owner, String name, String descriptor) {
        for (String c = owner; c != null; ) {
            ClassModel model = get(c);
            for (FieldModel f : model.fields()) {
                if (f.fieldName().equalsString(name) && f.fieldType().equalsString(descriptor)) {
                    return new ResolvedField(model, f);
                }
            }
            c = superName(model);
        }
        throw new CompileException("no field " + owner + "." + name + ":" + descriptor);
    }

    /** True if some class below {@code m}'s owner redeclares it, so a call through the owner type needs dispatch. */
    public boolean isOverridden(ResolvedMethod m) {
        String owner = m.ownerName();
        for (ClassModel c : classes.values()) {
            String name = c.thisClass().asInternalName();
            if (name.equals(owner) || !isSubclass(name, owner)) {
                continue;
            }
            MethodModel sub = declaredMethod(c, m.name(), m.descriptor());
            if (sub != null && !sub.flags().has(AccessFlag.STATIC) && !sub.flags().has(AccessFlag.PRIVATE)) {
                return true;
            }
        }
        return false;
    }
}
