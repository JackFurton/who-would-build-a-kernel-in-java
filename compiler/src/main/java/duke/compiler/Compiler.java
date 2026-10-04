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
    static final String CLASS_CLASS = "java/lang/Class";
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
    private final Set<VirtualCall> virtualCalls = new LinkedHashSet<>();
    private final Set<VirtualCall> interfaceCalls = new LinkedHashSet<>();
    /** Interface method selectors in itable order. */
    private final Map<Vtables.Key, Integer> selectors = new LinkedHashMap<>();
    private final Vtables vtables;
    private int multiArraySites;

    public Compiler(ClassPool pool) {
        this.pool = pool;
        this.layouts = new Layouts(pool);
        this.vtables = new Vtables(pool);
    }

    private record VirtualCall(String owner, String name, String descriptor) {}

    public Image compile(String entryClass, String entryMethod) {
        ClassPool.ResolvedMethod main = pool.resolveMethod(entryClass, entryMethod, "()V");
        if (!main.is(AccessFlag.STATIC)) {
            throw new CompileException("entry point " + entryClass + "." + entryMethod + " must be static");
        }
        // Every TIB is a Class object carrying its name as a String, so these are always in the image.
        requireClass(CLASS_CLASS);
        requireClass(STRING_CLASS);
        tibs.add(BYTE_ARRAY);
        requireClass(entryClass);
        String mainSymbol = requireMethod(main);

        do {
            while (!worklist.isEmpty()) {
                new MethodCompiler(this, worklist.removeFirst()).compile();
            }
            resolveDispatchTargets();
        } while (!worklist.isEmpty());

        closeTibs();
        emitStatics();
        emitTibs();
        emitStrings();
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

    Vtables vtables() {
        return vtables;
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

    private static String interfacesSymbol(String type) {
        return "interfaces:" + type;
    }

    private static String itableSymbol(String type) {
        return "itable:" + type;
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

    /** Records a dispatched call; every reachable override becomes reachable in turn. */
    void requireVirtual(String owner, String name, String descriptor) {
        virtualCalls.add(new VirtualCall(owner, name, descriptor));
    }

    /** Records a call through an interface and returns its itable index. */
    int requireInterfaceCall(String iface, String name, String descriptor) {
        interfaceCalls.add(new VirtualCall(iface, name, descriptor));
        return selectors.computeIfAbsent(new Vtables.Key(name, descriptor), k -> selectors.size());
    }

    /**
     * Rapid-type-analysis-style closure: for each dispatched call, compile the implementation each
     * reachable subclass would select. New methods can reach new classes, so the caller iterates.
     */
    private void resolveDispatchTargets() {
        for (VirtualCall call : List.copyOf(virtualCalls)) {
            for (String c : List.copyOf(classes)) {
                if (pool.get(c).flags().has(AccessFlag.INTERFACE) || !pool.isSubclass(c, call.owner())) {
                    continue;
                }
                ClassPool.ResolvedMethod impl = pool.findMethod(c, call.name(), call.descriptor());
                if (impl != null && !impl.is(AccessFlag.ABSTRACT) && !impl.is(AccessFlag.STATIC)) {
                    requireMethod(impl);
                }
            }
        }
        for (VirtualCall call : List.copyOf(interfaceCalls)) {
            for (String c : List.copyOf(classes)) {
                if (pool.get(c).flags().has(AccessFlag.INTERFACE) || !pool.allInterfaces(c).contains(call.owner())) {
                    continue;
                }
                ClassPool.ResolvedMethod impl = pool.selectMethod(c, call.name(), call.descriptor());
                if (impl != null && !impl.is(AccessFlag.ABSTRACT)) {
                    requireMethod(impl);
                }
            }
        }
    }

    String requireMethod(String owner, String name, String descriptor) {
        return requireMethod(pool.resolveMethod(owner, name, descriptor));
    }

    String requireStatic(ClassPool.ResolvedField f) {
        requireClass(f.ownerName());
        return staticSymbol(f.ownerName(), f.name());
    }

    /** A TIB for a type named the way class entries name it: internal name, or descriptor for arrays. */
    String requireTib(String type) {
        if (type.startsWith("[")) {
            return requireArrayTib(type);
        }
        pool.get(type);
        tibs.add(type);
        return tibSymbol(type);
    }

    /** {@code type} is an array descriptor such as {@code [I} or {@code [Ljava/lang/String;}. */
    String requireArrayTib(String type) {
        tibs.add(type);
        return tibSymbol(type);
    }

    /** Emits the per-site table {@code Heap.allocateMultiArray} walks; layout documented there. */
    String requireMultiArrayDescriptor(String type, int dimensions) {
        Section rodata = image.rodata;
        rodata.align(8);
        int start = rodata.size();
        rodata.emit32(dimensions);
        rodata.emit32(0);
        for (int level = 0; level < dimensions; level++) {
            String levelType = type.substring(level);
            rodata.emitReloc(Reloc.Kind.ABS64, requireArrayTib(levelType), 0);
            rodata.emit64(Layouts.width(levelType.substring(1)));
        }
        String symbol = "multianewarray:" + multiArraySites++;
        image.define(symbol, rodata, start, rodata.size() - start, Image.SymbolType.OBJECT);
        return symbol;
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

    /** Adds every TIB the emitted ones point at: superclasses, Object for arrays, element types. */
    private void closeTibs() {
        Deque<String> pending = new ArrayDeque<>(tibs);
        while (!pending.isEmpty()) {
            String type = pending.removeFirst();
            List<String> deps = new ArrayList<>();
            if (type.startsWith("[")) {
                deps.add(ClassPool.OBJECT);
                String element = elementType(type);
                if (element != null) {
                    deps.add(element);
                }
            } else {
                String superName = pool.superName(pool.get(type));
                if (superName != null) {
                    deps.add(superName);
                }
                deps.addAll(pool.allInterfaces(type));
            }
            for (String dep : deps) {
                if (tibs.add(dep)) {
                    pending.addLast(dep);
                }
            }
        }
        for (String type : tibs) {
            requireString(javaName(type));
        }
    }

    /** The element type of a reference array, or null for a primitive array. */
    static String elementType(String arrayType) {
        String element = arrayType.substring(1);
        if (element.startsWith("[")) {
            return element;
        }
        if (element.startsWith("L")) {
            return element.substring(1, element.length() - 1);
        }
        return null;
    }

    /** The name Class.getName() would report: dots for classes, descriptors for arrays. */
    static String javaName(String type) {
        return type.replace('/', '.');
    }

    /**
     * TIB layout (offsets in Layouts). A TIB is also the type's java.lang.Class instance: word 0 is
     * an ordinary object header. They live in .rodata, so Class can never have instance fields.
     * <pre>
     * [0]  object header: the TIB of java.lang.Class
     * [8]  super TIB, or 0 for Object; Object for arrays
     * [16] instance size, or element size for arrays (u32)
     * [20] flags: array, interface, reference array (u32)
     * [24] element TIB for reference arrays, else 0
     * [32] name, a String
     * [40] 0-terminated list of every interface the type implements, or 0 if none
     * [48] itable: one code pointer per interface selector, or 0 for classes without interfaces
     * [56] vtable: one code pointer per Vtables slot, 0 where nothing dispatches to it
     * </pre>
     */
    private void emitTibs() {
        Section rodata = image.rodata;
        for (String type : tibs) {
            if (type.startsWith("[") || pool.allInterfaces(type).isEmpty()) {
                continue;
            }
            rodata.align(8);
            int start = rodata.size();
            for (String iface : pool.allInterfaces(type)) {
                rodata.emitReloc(Reloc.Kind.ABS64, tibSymbol(iface), 0);
            }
            rodata.emit64(0);
            image.define(interfacesSymbol(type), rodata, start, rodata.size() - start, Image.SymbolType.OBJECT);
            if (!selectors.isEmpty() && !pool.get(type).flags().has(AccessFlag.INTERFACE)) {
                emitItable(type);
            }
        }
        for (String type : tibs) {
            boolean array = type.startsWith("[");
            String element = array ? elementType(type) : null;
            boolean isInterface = !array && pool.get(type).flags().has(AccessFlag.INTERFACE);
            String superName = array ? ClassPool.OBJECT : pool.superName(pool.get(type));
            int flags = (array ? Layouts.TIB_FLAG_ARRAY : 0)
                    | (isInterface ? Layouts.TIB_FLAG_INTERFACE : 0)
                    | (element != null ? Layouts.TIB_FLAG_REFERENCE_ARRAY : 0);

            rodata.align(8);
            int start = rodata.size();
            emitPointer(rodata, tibSymbol(CLASS_CLASS));
            emitPointer(rodata, superName == null ? null : tibSymbol(superName));
            rodata.emit32(array ? Layouts.width(type.substring(1)) : layouts.of(type).size());
            rodata.emit32(flags);
            emitPointer(rodata, element == null ? null : tibSymbol(element));
            emitPointer(rodata, strings.get(javaName(type)));
            emitPointer(rodata, image.isDefined(interfacesSymbol(type)) ? interfacesSymbol(type) : null);
            emitPointer(rodata, image.isDefined(itableSymbol(type)) ? itableSymbol(type) : null);
            String dispatchType = array || isInterface ? ClassPool.OBJECT : type;
            for (Vtables.Key key : vtables.layout(dispatchType)) {
                ClassPool.ResolvedMethod impl = pool.findMethod(dispatchType, key.name(), key.descriptor());
                String symbol = impl == null ? null : methodSymbol(impl.ownerName(), impl.name(), impl.descriptor());
                boolean compiled = symbol != null && queuedMethods.contains(symbol) && !impl.is(AccessFlag.ABSTRACT);
                emitPointer(rodata, compiled ? symbol : null);
            }
            image.define(tibSymbol(type), rodata, start, rodata.size() - start, Image.SymbolType.OBJECT);
        }
    }

    /** Entries stay 0 where the class doesn't implement the selector or nothing calls it. */
    private void emitItable(String type) {
        Section rodata = image.rodata;
        rodata.align(8);
        int start = rodata.size();
        for (Vtables.Key key : selectors.keySet()) {
            ClassPool.ResolvedMethod impl = pool.selectMethod(type, key.name(), key.descriptor());
            String symbol = impl == null ? null : methodSymbol(impl.ownerName(), impl.name(), impl.descriptor());
            boolean compiled = symbol != null && queuedMethods.contains(symbol) && !impl.is(AccessFlag.ABSTRACT);
            emitPointer(rodata, compiled ? symbol : null);
        }
        image.define(itableSymbol(type), rodata, start, rodata.size() - start, Image.SymbolType.OBJECT);
    }

    private static void emitPointer(Section section, String symbol) {
        if (symbol == null) {
            section.emit64(0);
        } else {
            section.emitReloc(Reloc.Kind.ABS64, symbol, 0);
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
