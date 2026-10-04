# Architecture

## Boot

1. UEFI firmware runs Limine from `EFI/BOOT/BOOTX64.EFI`. Limine reads `boot/limine/limine.conf`.
2. Limine loads `kernel.elf`. Its segments sit in the higher half at `0xffffffff80000000`.
   Limine scans the image for its requests: base revision 6, HHDM, memory map, executable
   address, framebuffer and RSDP. These are declared in Java as `static final long[]` in
   `duke.boot.Limine`. Build-time initialization turns them into data, and the compiler puts the
   class in every image and fails if that ever stops being possible. Limine writes response
   pointers into the arrays, switches to long mode with paging on, and jumps to `_start`.
3. `_start` comes from `Compiler.emitBootStub`. It switches to a 64 KiB stack in `.bss`,
   initializes `Kernel`, then calls `Kernel.main()`. If `main` returns, the CPU halts.

Everything after step 2 is compiled Java except the dozen instructions of `_start`.

At entry, interrupts are disabled and there is no IDT, so a fault before `Idt.load()` triple-faults
(QEMU, run with `-no-reboot`, then exits). SSE is disabled (Limine clears CR4 bits it doesn't
need), so the compiler emits no SSE instructions.

## Interrupts

`Magic.interruptStubs()` makes the compiler emit 256 entry stubs and a table of their addresses.
Each stub pushes a dummy error code where the CPU doesn't push one, then its vector, and jumps to a
common path. That path clears DF, saves every general-purpose register, and calls
`duke.kernel.x86.Interrupts.dispatch(frame)` with the address of the saved state. It restores
after, and returns with `iretq`. `Idt` writes the gates in Java and loads them with `lidt`.

We load our own GDT (`Gdt`: kernel code 0x08, data 0x10, and a TSS) before the IDT. Limine's lives in
bootloader-reclaimable memory, and the CPU reads the GDT on every interrupt. The TSS provides IST 1,
a separate stack for double faults. A page below the boot stack is left unmapped, so running off the
stack faults. That fault can't push its frame, so it escalates to a double fault, which lands on
the IST stack and gets reported. The double-fault entry stub also switches the prologue stack
checks off (`stack.limit = 0`), since the IST stack sits below the boot stack's limit.

`Interrupts.dispatch` runs a registered `Handler` if there is one. Otherwise it panics with the
exception name, error code, CR2 for page faults, and a register dump. Handlers must not allocate
for now: the bump allocator isn't reentrant.

## Memory

`PhysicalMemory` hands out 4 KiB frames from the "usable" regions of Limine's memory map,
tracked in a `FrameBitmap` (one bit per page, next-fit search). Frame 0 is never handed out.
Frames are named by physical address and touched through the higher-half direct map.

`KernelAddressSpace.activate()` then builds our own 4-level tables (`PageTable`) and switches
CR3. The kernel image is mapped from the compiler's section boundaries (`Magic.imageLayout()`):
text read-only and executable, rodata read-only, data and bss writable, and both NX when the CPU
has it. The direct map covers every region type Limine maps, with 2 MiB pages where alignment
allows, and uncached for the framebuffer. The virtual layout is unchanged, so execution simply
continues.

Last in `Kernel.init()`, the bootloader-reclaimable regions (Limine's page tables, GDT, stack
and responses) go to the frame allocator: 45 MiB under QEMU's 256M. `Limine.snapshot()` copies
every response into Java objects first, and every `Limine` accessor reads the copies.

`KernelHeap.init()` then moves Java allocation off the 4 MiB early arena in `.bss` into a 512 GiB
virtual region of its own. `Heap` stays a bump allocator, but when it runs off the committed end
it asks its `Backing` to map 2 MiB more of zeroed frames. The backing reports failure rather than
throwing: building an `OutOfMemoryError` would itself need the allocator. Nothing is freed until
there's a collector, below.

### Garbage collection

`duke.rt.Collector` is a non-moving mark-sweep, written in Java that never allocates while it
runs. Non-moving keeps identity hashes (address-derived) and `Magic.addressOf` results valid, and
needs no pointer fix-ups.

Roots are static reference fields and build-time reference arrays (both listed in tables the
compiler emits), plus the stack. Stacks are walked through the rbp chain with **precise stack
maps**. Every value a template-compiled method holds is in its frame at a call, never only in a
register, so every call site is a safepoint. At each call the compiler records which rbp-relative
slots hold references. The types come from `FrameTypes`, the JVM verifier's type pass reduced to
reference/non-reference, seeded by the class file's StackMapTable. Templates often pop operands
into registers before calling a helper, so each call also records how many operand slots it
already consumed. Those slots aren't in the frame any more.

Marking uses one bit per 8 heap bytes (a build-time array for the early arena, a committed side
table for the growable region) and an explicit mark stack. Sweeping walks objects by their
headers, coalesces dead runs into holes, chains them into an address-ordered free list, and hands
a dead tail back to the bump pointer. Free space is always walkable: holes carry a small marker
word that can't be a TIB address. Allocation bumps first, then takes holes, then collects once
committed memory passes twice the last live size (32 MiB minimum), and only then commits more.

`make conformance-gc` runs the whole conformance suite with a collection at every allocation
(about 50,000 collections). A slot missing from a stack map shows up there as a wrong result or a
GC panic. Interrupt handlers still must not allocate, and threads (M5) will need a safepoint
protocol.
Java objects still come from the fixed bump arena in `.bss` (see Objects below).

## Platform

`duke.kernel.acpi` finds the RSDP through Limine and walks the XSDT, verifying every table's
checksum. It parses the MADT (CPUs, I/O APICs, ISA interrupt overrides) and the HPET's address.
Device registers live in reserved physical memory outside the direct map, so
`KernelAddressSpace.mapDevice` maps them uncached into their own PML4 slot. QEMU runs with
`-smp 2`, so the CPU count is a real check.

Time comes from the local APIC timer. `Pic.disable()` remaps the legacy PICs to vectors
0xE0-0xEF and masks them, so a spurious IRQ can't land on a CPU exception vector. The APIC timer
is calibrated against the HPET's main counter over 10 ms, then runs periodic at 100 Hz into a
Java handler that counts ticks and sends EOI. `Kernel.init()` ends by enabling interrupts. Every
suite runs with the tick live. The GC-stress conformance run takes about 6,000 timer interrupts at
arbitrary instructions, in the middle of about 53,000 collections.

## Input and the shell

`IoApic` routes ISA IRQs to the boot CPU, applying the MADT's polarity and trigger overrides. The
PS/2 keyboard (IRQ 1, scan code set 1, US layout) and COM1's receive interrupt (IRQ 4) both push
characters into `Input`, a ring buffer of image arrays, so handlers never allocate. After the boot
log, `Kernel.main` runs `Shell`: line editing plus `help`, `uptime`, `mem`, `gc`, `cpus`, `echo`
and `panic`. `make shell-test` boots the real kernel with serial on pipes and the QEMU monitor on a
socket. It types over serial and as PS/2 keystrokes (`sendkey`) and checks the replies.

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
         [32] name (a String)   [40] interface list   [48] itable   [56] reference field offsets
         [64...] vtable
```

### Dispatch

`invokevirtual` compiles to a direct call whenever class hierarchy analysis proves a single
target (the method is private or final, its class is final, or nothing overrides it). Otherwise
it loads the receiver's TIB and calls through `[tib + 64 + 8*slot]`. Slots come from `Vtables`:
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
pointer (see Memory above for where the memory comes from). Nothing is freed yet.

A TIB is also its type's `java.lang.Class` object: word 0 is an ordinary object header pointing
at `Class`'s own TIB. So `getClass()` is one load, `Foo.class` is the TIB's address, and
`java.lang.Class` has no fields. It reads everything through `duke.rt.Tib`.

String literals are prebuilt objects in `.data`, Latin-1 only for now. `String`'s `value` field
layout is part of the contract between `dukec` and `kernel/src/java/lang/String.java`.

### Lambdas

`LambdaCompiler` does `LambdaMetafactory`'s job at build time. For each lambda or method reference
call site, it uses the JDK's `java.lang.classfile` builder to generate a real class:
`Caller$$Lambda$N`, which implements the functional interface, has a field per captured value, and
has a static `create` factory. Non-capturing lambdas get a singleton. The generated class goes
into the class pool like any other, and the `invokedynamic` compiles to `invokestatic create`.
Boxing, unboxing, casts and widening between the interface's erased signature and the
implementation are emitted as ordinary bytecode in the generated method.

`java.lang.invoke` in the kernel is stubs: javac needs the names to exist, nothing calls them.

### Class initialization

Classes initialize lazily, with JVM semantics (JVMS 5.5): on the first `new`, static field access
or static call, superclass first. Each class whose initialization runs code gets a one-byte flag
and a stub. Trigger sites compile to `cmp byte [flag], 0; jne skip; call stub`. No check is
emitted for classes with nothing to run, or inside the class itself or a subclass. The flag is set
before `<clinit>` runs, so cycles and self-references see default values exactly as on HotSpot.

Some initializers never run at boot. `BuildTimeInit` interprets a `<clinit>` at compile time when
it's straight-line code that only builds constants: pushes, array creation, array loads and
stores, and the class's own statics. The resulting arrays are emitted into `.data` with the normal
heap layout, the fields point at them, and the class needs no init stub or checks at all. Anything
else (a call, a branch, `new`) keeps lazy runtime init. This is safe because such an initializer
has no effect outside its own fields, so when it runs is unobservable. A fuller image heap
(running arbitrary initializers on the host, GraalVM-style) would need a heap snapshotter and a
policy for which classes are safe. That waits until something needs it.

### Runtime checks

Null dereferences, array bounds and division by zero are checked inline, and on failure call
`duke.rt.Runtime` inline too, which throws the JDK's exception with the JDK's message. The call
being inline matters: its return address sits inside the right `try` range and source line.

### Exceptions

`athrow` calls `duke.rt.Exceptions.raise`, plain Java. It walks the rbp chain and, for each
frame, looks the return address up in the method table, then in that method's exception table:
rows of (start, end, handler, catch TIB) in x86 code offsets, translated from the class file's
exception table after codegen. On a match it calls the `resumeAt` intrinsic. That resets rsp to
the frame's base (rbp minus its locals), sets rbp, pushes the exception (the JVM's handler entry
state) and jumps. `finally`, multi-catch and try-with-resources are just javac's bytecode on top.
Unwinding stops at an interrupt entry, since exceptions can't propagate out of an interrupt.

Stack overflow is a real, catchable `StackOverflowError`. Every prologue compares rsp against
`stack.limit`, which sits 16 KiB above the bottom of the stack. Crossing it calls a stub that lowers
the limit to 4 KiB above the bottom and throws from inside that reserve. The unwinder restores the
normal limit when it resumes in a handler. Overflowing the reserve too panics instead of
corrupting memory. The check is 18 bytes per method, about 12% of `.text` today.

An uncaught exception panics with `uncaught <toString>`, its stack trace and its causes.
`Throwable` captures return addresses at construction. The compiler flags Throwable constructors
and runtime plumbing as hidden so traces start where the JDK's would. `OutOfMemoryError` is still a
panic: there's no memory left to build it in.

### Backtraces

The compiler emits a method table (start, size, name, source file, line table) for every function
in `.text`, sorted by address. Line tables come from `LineNumber` entries seen during codegen,
plus a row for each slow-path stub carrying its site's line. `duke.rt.Backtrace` walks the rbp
chain (every method keeps a frame pointer, and `_start` zeroes rbp) and binary-searches the table.
Panics and CPU exception reports print Java-style frames:

```
PANIC: ArithmeticException: / by zero
  at duke.rt.Runtime.divideByZero(Runtime.java:23)
  at duke.panics.DeepTrace.inner(DeepTrace.java:15)
  at duke.panics.DeepTrace.middle(DeepTrace.java:19)
```

`checkcast`, `instanceof` and reference array stores decide exact TIB matches inline (plus null,
and stores into `Object[]`) and otherwise call `duke.rt.Types`, which walks the super chain, the
TIB's interface list, or array element types.

### Intrinsics

Native methods on `duke.rt.Magic` are compiled inline: port I/O (`outb`/`inb` and the wider
forms), raw memory access (`peek*`/`poke*`), `addressOf`, `halt`, `disableInterrupts`,
`enableInterrupts` and `pause`. A native method anywhere else is a compile error.

## What compiles today

Static, instance, virtual and interface methods (including defaults), exceptions, the core of
`java.util` (collections, `Arrays`, `Comparator`, `java.util.function`), type checks and casts, boxing, string concatenation, lambdas and method references, object and array allocation
(including multi-dimensional), constructors, int/long/boolean/byte/char/short arithmetic with Java semantics, all control flow
including both switch forms, static and instance fields, array loads and stores, string literals
and `String.length`/`charAt`.

Not yet, and each a clear compile error: floating point and monitors.

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
- `make ktest` runs every `static void test*()` in `tests/kernel` inside one booted kernel, for
  code HotSpot can't run (physical memory, page tables, interrupts). Failures are thrown
  `AssertionError`s, caught per test. The kernel exits QEMU through `isa-debug-exit` with the
  overall result, so the harness reads an exit status instead of watching serial.
- `make panic-tests` boots one kernel per file in `tests/panics` and checks the output contains
  the lines in the file's leading `// expect:` comments, in order: the panic line, then
  optionally backtrace frames. This covers the failure side of every
  runtime check (null, bounds, division, casts, array stores, allocation).
