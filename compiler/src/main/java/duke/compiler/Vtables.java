package duke.compiler;

import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.reflect.AccessFlag;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Virtual method slot assignment: a class's vtable is its superclass's, with overrides reusing the
 * inherited slot and new methods appended. Arrays and interfaces share java.lang.Object's.
 */
final class Vtables {

    record Key(String name, String descriptor) {}

    private final ClassPool pool;
    private final Map<String, List<Key>> cache = new HashMap<>();

    Vtables(ClassPool pool) {
        this.pool = pool;
    }

    List<Key> layout(String type) {
        if (type.startsWith("[") || pool.get(type).flags().has(AccessFlag.INTERFACE)) {
            type = ClassPool.OBJECT;
        }
        List<Key> cached = cache.get(type);
        if (cached != null) {
            return cached;
        }
        ClassModel model = pool.get(type);
        String superName = pool.superName(model);
        List<Key> slots = superName == null ? new ArrayList<>() : new ArrayList<>(layout(superName));
        for (MethodModel m : model.methods()) {
            if (isVirtual(m)) {
                Key key = new Key(m.methodName().stringValue(), m.methodType().stringValue());
                if (!slots.contains(key)) {
                    slots.add(key);
                }
            }
        }
        List<Key> result = List.copyOf(slots);
        cache.put(type, result);
        return result;
    }

    int slot(String type, String name, String descriptor) {
        int slot = layout(type).indexOf(new Key(name, descriptor));
        if (slot < 0) {
            throw new CompileException("no virtual method " + type + "." + name + descriptor);
        }
        return slot;
    }

    static boolean isVirtual(MethodModel m) {
        return !m.flags().has(AccessFlag.STATIC) && !m.flags().has(AccessFlag.PRIVATE)
                && !m.methodName().equalsString("<init>");
    }
}
