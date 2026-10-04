# Architecture

## Boot

1. UEFI firmware runs Limine from `EFI/BOOT/BOOTX64.EFI`. Limine reads `boot/limine/limine.conf`.
2. Limine loads `kernel.elf`. Its segments sit in the higher half at `0xffffffff80000000`.
   Limine finds the requests block (start marker, base revision 6 tag, end marker) in `.data`,
   switches to long mode with paging on, and jumps to `_start`.
3. `_start` comes from `Compiler.emitBootStub`. It switches to a 64 KiB stack in `.bss`,
   initializes `Kernel`, then calls `Kernel.main()`. If `main` returns, the CPU halts.

Everything after step 2 is compiled Java except the dozen instructions of `_start`.

At entry, interrupts are disabled and there is no IDT. Any fault triple-faults, and QEMU, run
with `-no-reboot`, exits. SSE is disabled (Limine clears CR4 bits it doesn't need), so the
compiler emits no SSE instructions.

## Compiler pipeline

```
ClassPool ─▶ Compiler (worklist from Kernel.main) ─▶ MethodCompiler per method ─▶ X64 into Image sections ─▶ link ─▶ ElfWriter
```

- **ClassPool** holds every class in the image. The world is closed: anything missing from the
  pool doesn't exist, which is what makes devirtualization by class hierarchy analysis sound.
- **Compiler** drives reachability. A method is compiled only once something calls it. A class
  becomes reachable when its statics, methods or literals are used, and that schedules its
  `<clinit>`.
- **MethodCompiler** is a template code generator. Each bytecode becomes a fixed instruction
  sequence. That's slow code, but simple enough to trust while the rest of the system gets built.
  An SSA IR with register allocation is on the roadmap.
- **X64** encodes instructions. Each form is pinned to clang's output in `X64Test`.
- **Image / ElfWriter** do layout and relocation (`PC32`, `ABS64`) and emit a static ELF with a
  symbol table, so `objdump` and `gdb` show Java method names.

### Execution model

The machine stack is the JVM operand stack. Every JVM slot, local or stack, is one 8-byte machine
slot. `long` takes two, exactly as in the JVM: the value sits in the lower-numbered local (the
deeper stack slot), with a padding slot above it. This keeps `dup2`, `pop2` and the `dup_x`
family trivially correct.

Int values have undefined upper 32 bits in their slot. Any code that consumes an int as 64 bits
(`i2l`, array indexing) extends it explicitly.

### Calling convention

```
caller:  push arg0 ... push argN      ; first argument deepest
         call Owner.name(desc)
         add rsp, 8 * argSlots
         push rax                      ; (twice for long results)

callee frame:
         [rbp + 16 + 8*(argSlots-1-i)]  argument / local slot i  (i < argSlots)
         [rbp + 8]                      return address
         [rbp]                          saved rbp
         [rbp - 8*(j+1)]                local slot argSlots + j
```

Results come back in `rax`. All registers are caller-saved. Symbols are
`owner.name(descriptor)`, for example `duke/kernel/Console.print(J)V`.

### Objects

```
object:  [0] TIB pointer   [8...] fields (superclass first, then largest-first)
array:   [0] TIB pointer   [8] int length   [12] padding   [16...] elements
TIB:     [0] header (Class's TIB)   [8] super TIB   [16] size   [20] flags   [24] element TIB
         [32] name (a String)   [40] interface list   [48] itable   [56...] vtable
```

### Dispatch

`invokevirtual` compiles to a direct call whenever class hierarchy analysis proves a single
target (the method is private or final, its class is final, or nothing overrides it). Otherwise
it loads the receiver's TIB and calls through `[tib + 56 + 8*slot]`. Slots come from `Vtables`:
superclass slots first, overrides reuse the inherited slot.

Vtables don't defeat tree-shaking. The compiler records every dispatched `(owner, name,
descriptor)` and, after each round of compilation, compiles the implementation every reachable
subclass would select, repeating until nothing new turns up. Slots nothing dispatches through stay
0.

Interface calls use a global selector per method name and descriptor called through any interface.
Every class that implements interfaces gets an itable indexed by selector, so `invokeinterface`
is load TIB, load itable, call `[itable + 8*selector]`. Constant time, at the cost of mostly-empty
tables, which is cheap while the kernel has a few hundred classes. Selection follows JVMS 5.4.6,
so default methods, `I.super.m()` and `invokevirtual` calls that land on an inherited default all
take the same path.

`new C` compiles to a call to `duke.rt.Heap.allocateObject(tib, size)`, plain Java bumping a
pointer through a 16 MiB arena in `.bss`. Nothing is freed yet.

A TIB is also its type's `java.lang.Class` object: word 0 is an ordinary object header pointing
at `Class`'s own TIB. So `getClass()` is one load, `Foo.class` is the TIB's address, and
`java.lang.Class` has no fields. It reads everything through `duke.rt.Tib`.

String literals are prebuilt objects in `.data`, Latin-1 only for now. `String`'s `value` field
layout is part of the contract between `dukec` and `kernel/src/java/lang/String.java`.

### Class initialization

Classes initialize lazily, with JVM semantics (JVMS 5.5): on the first `new`, static field access
or static call, superclass first. Each class whose initialization runs code gets a one-byte flag
and a stub. Trigger sites compile to `cmp byte [flag], 0; jne skip; call stub`. No check is
emitted for classes with nothing to run, or inside the class itself or a subclass. The flag is set
before `<clinit>` runs, so cycles and self-references see default values exactly as on HotSpot.

### Runtime checks

Null dereferences, array bounds and division by zero are checked inline. Failures jump to
per-method slow paths that call `duke.rt.Runtime`, which panics. There are no exceptions yet.

`checkcast`, `instanceof` and reference array stores decide exact TIB matches inline (plus null,
and stores into `Object[]`) and otherwise call `duke.rt.Types`, which walks the super chain, the
TIB's interface list, or array element types.

### Intrinsics

Native methods on `duke.rt.Magic` are compiled inline: port I/O (`outb`/`inb` and the wider
forms), raw memory access (`peek*`/`poke*`), `addressOf`, `halt`, `disableInterrupts`,
`enableInterrupts` and `pause`. A native method anywhere else is a compile error.

## What compiles today

Static, instance, virtual and interface methods (including defaults), type checks and casts, boxing, string concatenation, object and array allocation
(including multi-dimensional), constructors, int/long/boolean/byte/char/short arithmetic with Java semantics, all control flow
including both switch forms, static and instance fields, array loads and stores, string literals
and `String.length`/`charAt`.

Not yet, and each a clear compile error: exceptions, floating point, lambdas and method
references (`invokedynamic`), monitors.

String concatenation works because the kernel compiles with `javac -XDstringConcat=inline`, which
turns `+` into `StringBuilder` calls instead of an `invokedynamic`. Every place that compiles
kernel sources has to pass it: the Makefile, `tools/Harness.java` and `CompilerTest`.

## Testing

- `make unit-test` runs encoder golden tests, ELF structure checks, and compiler error-message
  tests.
- `make boot-test` boots the real kernel and waits for `DUKE-BOOT-OK` on serial.
- `make conformance` runs `tools/Conformance.java`, which executes every test in
  `tests/conformance` on HotSpot, generates a kernel that runs the same tests, boots it and
  compares the results. Add a test by adding a non-private, no-argument static method with a
  primitive result.
- `make panic-tests` boots one kernel per file in `tests/panics` and checks it dies with the
  panic line named in the file's `// expect:` comment. This covers the failure side of every
  runtime check (null, bounds, division, casts, array stores, allocation).
