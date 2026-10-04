# who would build a kernel in Java?

[![CI](https://github.com/JackFurton/who-would-build-a-kernel-in-java/actions/workflows/ci.yml/badge.svg)](https://github.com/JackFurton/who-would-build-a-kernel-in-java/actions/workflows/ci.yml)

Us, apparently. **Duke** is an x86_64 kernel written in Java, with no JVM underneath. `dukec`, an
ahead-of-time compiler that is also written in Java, turns the kernel's bytecode straight into
machine code and a bootable ELF. Limine boots it. No C, no assembler, no linker.

```
kernel/src/**/*.java ──javac --system none──▶ .class ──dukec──▶ kernel.elf ──Limine (UEFI)──▶ QEMU / hardware
```

Right now it boots, talks over the serial port, and agrees with HotSpot on every test in the
conformance suite. Objects, interrupts, memory management and the rest are on the
[roadmap](https://github.com/JackFurton/who-would-build-a-kernel-in-java/milestones).

```
Duke: hello from Java on bare metal
bytecode arithmetic check: 6 * 7 = 42
DUKE-BOOT-OK
```

## How it works

- **The kernel is its own `java.base`.** Kernel sources compile with `javac --system none`, so
  `java.lang.Object` and `java.lang.String` are ours, in `kernel/src/java/lang`. Nothing from the
  JDK ends up in the image.
- **`dukec` compiles the whole program.** It reads class files with `java.lang.classfile`, starts
  at `Kernel.main`, compiles every reachable method to x86-64, lays out objects, string literals
  and class metadata, and writes the ELF itself.
- **Hardware access goes through intrinsics.** `duke.rt.Magic` declares native methods like
  `outb`, `peekLong` and `halt` that the compiler replaces with inline instructions.
- **Correctness is tested differentially.** Every test in `tests/conformance` runs on HotSpot and
  inside the booted kernel. The results must match exactly.

The details (calling convention, object layout, boot sequence) are in
[docs/architecture.md](docs/architecture.md).

## Building

You need JDK 25, `make`, `git`, QEMU (`qemu-system-x86_64`) and x86_64 UEFI firmware.
Homebrew's `qemu` ships the firmware. On Debian or Ubuntu, `apt install qemu-system-x86 ovmf`
covers both. Put the firmware somewhere unusual? Set `OVMF=/path/to/OVMF_CODE.fd`.

```sh
make run          # build and boot; serial output in your terminal, Ctrl-A X to quit
make test         # compiler unit tests, boot test, conformance suite
make disasm       # objdump the kernel (symbols included)
```

The first build downloads a pinned Limine release into `build/limine`.

## Layout

| Path | What |
| --- | --- |
| `compiler/` | `dukec`: class loading, code generation (`MethodCompiler`), x86-64 encoder (`asm/`), ELF writer (`image/`) |
| `kernel/src/` | The kernel's `java.base`: `java.lang`, `duke.rt` (runtime and intrinsics), `duke.kernel` |
| `tests/conformance/` | Differential tests, run on HotSpot and in the kernel |
| `tools/` | ESP layout, QEMU launch, boot test, conformance harness |
| `boot/` | Limine config |

## Contributing

Issues are grouped into milestones. Look for
[`good first issue`](https://github.com/JackFurton/who-would-build-a-kernel-in-java/labels/good%20first%20issue).
Run `make test` before opening a PR. CI runs the same targets.
