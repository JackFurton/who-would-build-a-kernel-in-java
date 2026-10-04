package duke.compiler;

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
    static final String BYTE_ARRAY = "[B";

    static final String HEAP_ARENA = "heap.arena";
    static final int HEAP_ARENA_SIZE = 16 * 1024 * 1024;

    private static final int BOOT_STACK_SIZE = 64 * 1024;
    private static final int LIMINE_BASE_REVISION = 6;

    private final ClassPool pool;
    private final Layouts layouts;
    private final Image image = new Image();

    private final Deque<ClassPool.ResolvedMethod> worklist = new ArrayDeque<>();
    private final Set<String> queuedMethods = new LinkedHashSet<>();
    /** Reachable classes in initialization order: every class after its superclass. */
    private final Set<String> classes = new LinkedHashSet<>();
    private final Map<String, String> strings = new LinkedHashMap<>();
    private final Set<String> tibs = new LinkedHashSet<>();

    public Compiler(ClassPool pool) {
        this.pool = pool;
        this.layouts = new Layouts(pool);
    }

    public Image compile(String entryClass, String entryMethod) {
        ClassPool.ResolvedMethod main = pool.resolveMethod(entryClass, entryMethod, "()V");
        if (!main.is(AccessFlag.STATIC)) {
            throw new CompileException("entry point " + entryClass + "." + entryMethod + " must be static");
        }
        requireClass(entryClass);
        String mainSymbol = requireMethod(main);

        while (!worklist.isEmpty()) {
            ClassPool.ResolvedMethod m = worklist.removeFirst();
            new MethodCompiler(this, m).compile();
        }

        emitStatics();
        emitStrings();
        emitTibs();
        emitBootStub(mainSymbol);
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

    static String methodSymbol(String owner, String name, String descriptor) {
        return owner + "." + name + descriptor;
    }

    static String staticSymbol(String owner, String name) {
        return owner + "::" + name;
    }

    static String tibSymbol(String type) {
        return "tib:" + type;
    }

    /** Marks a class reachable, which also schedules its static initializer. */
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
        MethodModel clinit = pool.declaredMethod(model, "<clinit>", "()V");
        if (clinit != null) {
            requireMethod(new ClassPool.ResolvedMethod(model, clinit));
        }
    }

    String requireMethod(ClassPool.ResolvedMethod m) {
        String symbol = methodSymbol(m.ownerName(), m.name(), m.descriptor());
        if (queuedMethods.add(symbol)) {
            worklist.addLast(m);
        }
        return symbol;
    }

    String requireMethod(String owner, String name, String descriptor) {
        return requireMethod(pool.resolveMethod(owner, name, descriptor));
    }

    String requireStatic(ClassPool.ResolvedField f) {
        requireClass(f.ownerName());
        return staticSymbol(f.ownerName(), f.name());
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

    /**
     * TIB layout: [0] super TIB or 0, [8] instance size (element size for arrays), [12] flags
     * (bit 0: array). Virtual dispatch tables will follow at [16].
     */
    private void emitTibs() {
        Section rodata = image.rodata;
        for (String type : tibs) {
            rodata.align(8);
            int start = rodata.size();
            if (type.startsWith("[")) {
                rodata.emitReloc(Reloc.Kind.ABS64, tibSymbol("java/lang/Object"), 0);
                rodata.emit32(Layouts.width(type.substring(1)));
                rodata.emit32(1);
                requireClassData("java/lang/Object");
            } else {
                String superName = pool.superName(pool.get(type));
                if (superName == null) {
                    rodata.emit64(0);
                } else {
                    rodata.emitReloc(Reloc.Kind.ABS64, tibSymbol(superName), 0);
                }
                rodata.emit32(layouts.of(type).size());
                rodata.emit32(0);
            }
            image.define(tibSymbol(type), rodata, start, 16, Image.SymbolType.OBJECT);
        }
    }

    private void requireClassData(String name) {
        if (!tibs.contains(name)) {
            throw new CompileException("array TIBs need " + name + " in the image");
        }
    }

    /** Switches to our own stack, runs every class initializer in order, then calls the entry point. */
    private void emitBootStub(String mainSymbol) {
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
        List<String> initializers = new ArrayList<>();
        for (String name : classes) {
            if (pool.declaredMethod(pool.get(name), "<clinit>", "()V") != null) {
                initializers.add(methodSymbol(name, "<clinit>", "()V"));
            }
        }
        for (String clinit : initializers) {
            a.call(clinit);
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
