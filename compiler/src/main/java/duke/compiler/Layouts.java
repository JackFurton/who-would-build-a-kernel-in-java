package duke.compiler;

import java.lang.classfile.ClassModel;
import java.lang.classfile.FieldModel;
import java.lang.reflect.AccessFlag;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Object layout. Every object starts with an 8-byte pointer to its type information block (TIB);
 * arrays follow it with a 4-byte length and 4 bytes of padding so elements start 16-byte aligned.
 */
public final class Layouts {

    public static final int HEADER_SIZE = 8;
    public static final int ARRAY_LENGTH_OFFSET = 8;
    public static final int ARRAY_DATA_OFFSET = 16;

    /** Type information block fields; Compiler.emitTibs documents each. Mirrored in duke.rt.Tib. */
    public static final int TIB_SUPER = 8;
    public static final int TIB_SIZE = 16;
    public static final int TIB_FLAGS = 20;
    public static final int TIB_ELEMENT = 24;
    public static final int TIB_NAME = 32;
    public static final int TIB_INTERFACES = 40;
    public static final int TIB_ITABLE = 48;
    public static final int TIB_REFERENCE_FIELDS = 56;
    public static final int TIB_VTABLE = 64;

    public static final int TIB_FLAG_ARRAY = 1;
    public static final int TIB_FLAG_INTERFACE = 2;
    public static final int TIB_FLAG_REFERENCE_ARRAY = 4;

    public record ClassLayout(int size, Map<String, Integer> fieldOffsets, List<Integer> referenceOffsets) {

        public int offsetOf(String field) {
            Integer offset = fieldOffsets.get(field);
            if (offset == null) {
                throw new CompileException("no instance field " + field);
            }
            return offset;
        }
    }

    private final ClassPool pool;
    private final Map<String, ClassLayout> cache = new HashMap<>();

    public Layouts(ClassPool pool) {
        this.pool = pool;
    }

    /**
     * Superclass fields first, then this class's fields largest first so they pack without gaps.
     * Keys are bare field names: within one class javac never emits two fields with the same name.
     */
    public ClassLayout of(String className) {
        ClassLayout cached = cache.get(className);
        if (cached != null) {
            return cached;
        }
        ClassModel model = pool.get(className);
        String superName = pool.superName(model);
        Map<String, Integer> offsets = new LinkedHashMap<>();
        List<Integer> references = new ArrayList<>();
        int size = HEADER_SIZE;
        if (superName != null) {
            ClassLayout sup = of(superName);
            offsets.putAll(sup.fieldOffsets());
            references.addAll(sup.referenceOffsets());
            size = sup.size();
        }
        List<FieldModel> fields = new ArrayList<>();
        for (FieldModel f : model.fields()) {
            if (!f.flags().has(AccessFlag.STATIC)) {
                fields.add(f);
            }
        }
        fields.sort(Comparator.comparingInt((FieldModel f) -> -width(f.fieldType().stringValue())));
        for (FieldModel f : fields) {
            int w = width(f.fieldType().stringValue());
            size = (size + w - 1) & -w;
            offsets.put(f.fieldName().stringValue(), size);
            char kind = f.fieldType().stringValue().charAt(0);
            if (kind == 'L' || kind == '[') {
                references.add(size);
            }
            size += w;
        }
        ClassLayout layout = new ClassLayout((size + 7) & -8, offsets, List.copyOf(references));
        cache.put(className, layout);
        return layout;
    }

    /** Storage width in bytes of a value with the given field descriptor. */
    public static int width(String descriptor) {
        return switch (descriptor.charAt(0)) {
            case 'Z', 'B' -> 1;
            case 'C', 'S' -> 2;
            case 'I', 'F' -> 4;
            case 'J', 'D', 'L', '[' -> 8;
            default -> throw new IllegalArgumentException("bad descriptor " + descriptor);
        };
    }

    /** Whether loading a narrow value of this descriptor sign-extends. */
    public static boolean signed(String descriptor) {
        char c = descriptor.charAt(0);
        return c == 'B' || c == 'S';
    }
}
