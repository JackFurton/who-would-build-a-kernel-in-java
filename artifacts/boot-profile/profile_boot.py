#!/usr/bin/env python3
"""Reproduce stock boots, isolated phase profiles, and a narrow wide-zero probe.

All generated sources live under build/boot-profile. Every image retains the
stock boot workload; profiled images report only after the first prompt.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import selectors
import shlex
import shutil
import statistics
import subprocess
import time

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "build/boot-profile"
BASE_COMMIT = "df11ac0634982d9d5b0da4feb36bbc7cdb65e75c"
BASE_SOURCE = OUT / "baseline"
JAVA_HOME = os.environ.get("JAVA_HOME")
DEFAULT_JDK = (str(Path(JAVA_HOME) / "bin") if JAVA_HOME else
               "/opt/homebrew/opt/openjdk@25/bin" if Path("/opt/homebrew/opt/openjdk@25/bin").exists() else
               str(Path(shutil.which("javac") or "/usr/bin/javac").parent))
JDK = Path(os.environ.get("DUKE_PROFILE_JDK", DEFAULT_JDK))
QEMU = os.environ.get("DUKE_PROFILE_QEMU", shutil.which("qemu-system-x86_64") or "qemu-system-x86_64")
FIRMWARE_PATHS = ["/opt/homebrew/share/qemu/edk2-x86_64-code.fd",
                  "/usr/local/share/qemu/edk2-x86_64-code.fd", "/usr/share/OVMF/OVMF_CODE_4M.fd",
                  "/usr/share/OVMF/OVMF_CODE.fd", "/usr/share/qemu/OVMF.fd"]
OVMF = os.environ.get("OVMF", next((p for p in FIRMWARE_PATHS if Path(p).exists()), ""))
PHASES = [
    "entry", "serial_init", "limine_revision", "limine_snapshot", "gdt", "idt",
    "physical_memory_init", "page_tables", "kernel_heap_init", "framebuffer_init",
    "reclaim_bootloader", "acpi", "madt", "hpet_discovery", "pic", "local_apic",
    "timer_init_and_10ms_calibration", "scheduler_init", "ioapic", "ps2_keyboard",
    "serial_input", "enable_interrupts", "greeting_and_exception_demo", "memory_map_output",
    "memory_summary_output", "allocate_512MiB_and_gc", "platform_output",
    "explicit_100ms_sleep", "timer_output_and_boot_marker", "first_shell_prompt",
]
DETAILS = ["gc_clear_marks", "gc_roots", "gc_drain", "gc_sweep_inclusive",
           "gc_tail_zero_nested_in_sweep", "heap_commit_data", "heap_commit_bitmap", "heap_hole_zero"]
PROFILED = {"phases", "detail", "widezero"}


def command(args, log=None):
    if log:
        with Path(log).open("w") as f:
            subprocess.run([str(a) for a in args], cwd=ROOT, stdout=f, stderr=subprocess.STDOUT, check=True)
    else:
        subprocess.run([str(a) for a in args], cwd=ROOT, check=True)


def replace_once(text, old, new):
    if text.count(old) != 1:
        raise RuntimeError(f"Expected one occurrence: {old!r}, found {text.count(old)}")
    return text.replace(old, new, 1)


def prepare_baseline():
    OUT.mkdir(parents=True, exist_ok=True)
    BASE_SOURCE.mkdir(exist_ok=True)
    # Both variants start from this immutable commit, even on the optimized branch.
    archive = OUT / "baseline.tar"
    command(["git", "archive", "--format=tar", f"--output={archive}", BASE_COMMIT,
             "compiler/src/main/java", "kernel/src", "boot"])
    command(["tar", "-xf", archive, "-C", BASE_SOURCE])
    efi = OUT / "BOOTX64.EFI"
    if not efi.exists():
        existing = ROOT / "build/limine/BOOTX64.EFI"
        if not existing.exists():
            command(["make", "build/limine/BOOTX64.EFI"])
        shutil.copy2(existing, efi)


def instrument(src):
    path = src / "duke/kernel/Kernel.java"
    text = path.read_text()
    text = replace_once(text, "public static void main() {\n        init();",
                        "public static void main() {\n        BootProfile.begin();\n        init();")
    init_lines = [
        "Serial.init();", 'Panic.panic("Limine doesn\'t support base revision 6");\n        }',
        "Limine.snapshot();", "Gdt.ensureLoaded();", "Idt.load();", "PhysicalMemory.init();",
        "KernelAddressSpace.activate();", "KernelHeap.init();", "FramebufferConsole.init();",
        "reclaimed = PhysicalMemory.reclaimBootloaderMemory();", "Acpi.init();", "Madt.init();",
        "Hpet.init();", "Pic.disable();", "LocalApic.init();", "Timer.init();", "Scheduler.init();",
        "IoApic.init();", "Ps2Keyboard.init();", "Serial.enableInput();", "Magic.enableInterrupts();",
    ]
    for i, line in enumerate(init_lines, 1):
        text = replace_once(text, line, line + f"\n        BootProfile.mark({i});")
    text = replace_once(text, "        printMemoryMap();", "        BootProfile.mark(22);\n        printMemoryMap();\n        BootProfile.mark(23);")
    text = replace_once(text, "        for (int i = 0; i < 512; i++) {", "        BootProfile.mark(24);\n        for (int i = 0; i < 512; i++) {")
    text = replace_once(text, '        Console.println("gc: allocated', '        BootProfile.mark(25);\n        Console.println("gc: allocated')
    text = replace_once(text, "        Timer.sleep(100);", "        BootProfile.clockStart();\n        BootProfile.mark(26);\n        Timer.sleep(100);\n        BootProfile.mark(27);\n        BootProfile.clockEnd();")
    text = replace_once(text, "        Shell.run();", "        BootProfile.mark(28);\n        Shell.run();")
    path.write_text(text)
    path = src / "duke/kernel/Shell.java"
    text = replace_once(path.read_text(), "            Console.print(PROMPT);", "            Console.print(PROMPT);\n            BootProfile.finish();")
    path.write_text(text)
    (src / "duke/kernel/BootProfile.java").write_text('''package duke.kernel;

import duke.rt.Magic;
import duke.kernel.time.HpetClock;

public final class BootProfile {
    private static final long[] TIMES = new long[30];
    private static final long[] DETAIL_TIMES = new long[8];
    private static final long[] DETAIL_COUNTS = new long[8];
    private static final long[] DETAIL_BYTES = new long[8];
    private static long clockNanos;
    private static long clockTsc;
    private static long elapsedNanos;
    private static long elapsedTsc;
    private static boolean done;

    public static void begin() {
        Magic.outb(0xe9, 'K');
        mark(0);
    }
    public static void mark(int id) {
        TIMES[id] = Magic.readTimestamp();
    }
    public static void add(int id, long start, long bytes) {
        DETAIL_TIMES[id] += Magic.readTimestamp() - start;
        DETAIL_COUNTS[id]++;
        DETAIL_BYTES[id] += bytes;
    }
    public static void clockStart() {
        clockNanos = HpetClock.nanos();
        clockTsc = Magic.readTimestamp();
    }
    public static void clockEnd() {
        elapsedTsc = Magic.readTimestamp() - clockTsc;
        elapsedNanos = HpetClock.nanos() - clockNanos;
    }
    private static void number(long n) {
        long divisor = 1;
        while (n / divisor >= 10) divisor *= 10;
        while (divisor > 0) {
            Magic.outb(0xe9, '0' + (int) (n / divisor));
            n %= divisor;
            divisor /= 10;
        }
    }
    public static void finish() {
        if (done) return;
        mark(29);
        done = true;
        Magic.outb(0xe9, 'R');
        Magic.outb(0xe9, '\\n');
        number(elapsedTsc); Magic.outb(0xe9, ' '); number(elapsedNanos); Magic.outb(0xe9, '\\n');
        for (int i = 0; i < TIMES.length; i++) {
            number(i); Magic.outb(0xe9, ' '); number(TIMES[i]); Magic.outb(0xe9, '\\n');
        }
        for (int i = 0; i < DETAIL_TIMES.length; i++) {
            Magic.outb(0xe9, 'D'); Magic.outb(0xe9, ' ');
            number(i); Magic.outb(0xe9, ' '); number(DETAIL_TIMES[i]); Magic.outb(0xe9, ' ');
            number(DETAIL_COUNTS[i]); Magic.outb(0xe9, ' '); number(DETAIL_BYTES[i]); Magic.outb(0xe9, '\\n');
        }
        Magic.outb(0xe9, 'E'); Magic.outb(0xe9, '\\n');
    }
}
''')


def instrument_detail(src):
    path = src / "duke/rt/Collector.java"
    text = replace_once(path.read_text(), "package duke.rt;", "package duke.rt;\n\nimport duke.kernel.BootProfile;")
    text = replace_once(text, "        clearMarks();", "        long profileTime = Magic.readTimestamp();\n        clearMarks();\n        BootProfile.add(0, profileTime, 0);\n        profileTime = Magic.readTimestamp();")
    text = replace_once(text, "        drain();", "        BootProfile.add(1, profileTime, 0);\n        profileTime = Magic.readTimestamp();\n        drain();\n        BootProfile.add(2, profileTime, 0);\n        profileTime = Magic.readTimestamp();")
    text = replace_once(text, "        long live = sweep();", "        long live = sweep();\n        BootProfile.add(3, profileTime, 0);")
    text = replace_once(text, "                Magic.fillMemory(run, 0, to - run);", "                long profileZero = Magic.readTimestamp();\n                Magic.fillMemory(run, 0, to - run);\n                BootProfile.add(4, profileZero, to - run);")
    path.write_text(text)
    path = src / "duke/rt/Heap.java"
    text = replace_once(path.read_text(), "package duke.rt;", "package duke.rt;\n\nimport duke.kernel.BootProfile;")
    text = replace_once(text, "                Magic.fillMemory(hole, 0, size);", "                long profileZero = Magic.readTimestamp();\n                Magic.fillMemory(hole, 0, size);\n                BootProfile.add(7, profileZero, size);")
    text = replace_once(text, "        if (!backing.commit(end, newEnd - end) || !backing.commit(marksFrom, marksTo - marksFrom)) {", "        long profileCommit = Magic.readTimestamp();\n        boolean dataCommitted = backing.commit(end, newEnd - end);\n        BootProfile.add(5, profileCommit, newEnd - end);\n        boolean marksCommitted = false;\n        if (dataCommitted) {\n            profileCommit = Magic.readTimestamp();\n            marksCommitted = backing.commit(marksFrom, marksTo - marksFrom);\n            BootProfile.add(6, profileCommit, marksTo - marksFrom);\n        }\n        if (!dataCommitted || !marksCommitted) {")
    path.write_text(text)


def build(variant):
    OUT.mkdir(parents=True, exist_ok=True)
    target = OUT / variant
    target.mkdir(exist_ok=True)
    compiler_src = BASE_SOURCE / "compiler/src/main/java"
    wide = variant in ("widezero", "widezero-stock")
    if wide:
        compiler_src = target / "compiler-src"
        shutil.copytree(BASE_SOURCE / "compiler/src/main/java", compiler_src, dirs_exist_ok=True)
        path = compiler_src / "duke/compiler/asm/X64.java"
        text = replace_once(path.read_text(), "    /** Stores al into rcx bytes at [rdi]. */\n    public void repStosb() {", "    /** Stores rax into rcx eight-byte words at [rdi]. */\n    public void repStosq() {\n        out.emit8(0xF3);\n        out.emit8(0x48);\n        out.emit8(0xAB);\n    }\n\n    /** Stores al into rcx bytes at [rdi]. */\n    public void repStosb() {")
        path.write_text(text)
        path = compiler_src / "duke/compiler/MethodCompiler.java"
        text = replace_once(path.read_text(), '            case "fillMemory" -> {', '            case "zeroMemoryWords" -> {\n                popLong(RCX);\n                popLong(Reg.RDI);\n                a.movImm32(RAX, 0);\n                a.repStosq();\n            }\n            case "fillMemory" -> {')
        path.write_text(text)
    compiler = target / "compiler"
    compiler.mkdir(exist_ok=True)
    command([JDK / "javac", "-Xlint:all", "-Werror", "-d", compiler,
             *sorted(compiler_src.rglob("*.java"))], target / "compiler.log")
    source = target / "src"
    shutil.copytree(BASE_SOURCE / "kernel/src", source, dirs_exist_ok=True)
    if variant in PROFILED:
        instrument(source)
    if variant in ("detail", "widezero"):
        instrument_detail(source)
    if wide:
        path = source / "duke/rt/Magic.java"
        text = replace_once(path.read_text(), "    public static native void copyMemory(long destination, long source, long bytes);",
                            "    public static native void copyMemory(long destination, long source, long bytes);\n\n    /** Zero exactly {@code words * 8} bytes of aligned ordinary RAM; {@code words} must be nonnegative. */\n    public static native void zeroMemoryWords(long destination, long words);")
        path.write_text(text)
        path = source / "duke/rt/Collector.java"
        text = replace_once(path.read_text(), "Magic.fillMemory(run, 0, to - run);", "Magic.zeroMemoryWords(run, (to - run) >>> 3);")
        path.write_text(text)
    classes = target / "classes"
    command([JDK / "javac", "--system", "none", "-XDstringConcat=inline", "--module-source-path",
             f"java.base={source}", "-d", classes, *sorted(source.rglob("*.java"))], target / "javac.log")
    command([JDK / "java", "-cp", compiler, "duke.compiler.Main", "--classes", classes,
             "--entry", "duke/kernel/Kernel.main", "--output", target / "kernel.elf",
             "--map", target / "kernel.map"], target / "dukec.log")
    esp = target / "esp"
    (esp / "EFI/BOOT").mkdir(parents=True, exist_ok=True)
    (esp / "boot/limine").mkdir(parents=True, exist_ok=True)
    shutil.copy2(OUT / "BOOTX64.EFI", esp / "EFI/BOOT/BOOTX64.EFI")
    shutil.copy2(BASE_SOURCE / "boot/limine.conf", esp / "boot/limine/limine.conf")
    shutil.copy2(target / "kernel.elf", esp / "boot/kernel.elf")
    print(f"Built {variant}: {(target / 'kernel.elf').stat().st_size} bytes", flush=True)


def run(variant, trial, timeout):
    if not OVMF or not Path(OVMF).is_file():
        raise FileNotFoundError("Set OVMF to the x86_64 UEFI firmware image")
    target = OUT / variant
    run_dir = target / f"run-{trial:02d}"
    run_dir.mkdir(parents=True, exist_ok=True)
    debug = run_dir / "debug.log"
    args = [QEMU, "-M", "q35", "-m", "256M", "-smp", "2", "-accel", "tcg",
            "-display", "none", "-no-reboot", "-monitor", "none",
            "-drive", f"if=pflash,format=raw,readonly=on,file={OVMF}",
            "-drive", f"format=raw,file=fat:rw:{target / 'esp'}", "-serial", "stdio",
            "-debugcon", f"file:{debug}"]
    data = bytearray()
    started = time.monotonic_ns()
    observed = {}
    markers = {"first_kernel_serial": b"Duke: hello", "boot_ok": b"DUKE-BOOT-OK", "prompt": b"duke> "}
    with (run_dir / "qemu.log").open("wb") as error:
        proc = subprocess.Popen(args, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=error)
        selector = selectors.DefaultSelector()
        selector.register(proc.stdout, selectors.EVENT_READ)
        try:
            while (time.monotonic_ns() - started) / 1e9 < timeout:
                for key, _ in selector.select(0.02):
                    chunk = os.read(key.fileobj.fileno(), 65536)
                    if chunk:
                        data.extend(chunk)
                        now = time.monotonic_ns()
                        for name, needle in markers.items():
                            if name not in observed and needle in data:
                                observed[name] = (now - started) / 1e6
                if b"PANIC:" in data:
                    raise RuntimeError(f"Kernel panic; see {run_dir}")
                if "prompt" in observed:
                    if variant not in PROFILED or (debug.exists() and debug.read_bytes().endswith(b"E\n")):
                        break
                if proc.poll() is not None:
                    raise RuntimeError(f"QEMU exited {proc.returncode}; see {run_dir}")
            else:
                raise RuntimeError(f"Boot timeout; see {run_dir}")
        finally:
            proc.terminate()
            try:
                proc.wait(5)
            except subprocess.TimeoutExpired:
                proc.kill()
                proc.wait()
            selector.close()
            (run_dir / "serial.log").write_bytes(data)
    public_args = [a.replace(str(ROOT), "$REPO").replace(OVMF, "$OVMF") for a in args]
    public_args[0] = "qemu-system-x86_64"
    result = {"variant": variant, "trial": trial, "host_ms": observed, "qemu_command": public_args,
              "base_commit": BASE_COMMIT,
              "image_sha256": hashlib.sha256((target / "kernel.elf").read_bytes()).hexdigest()}
    if variant in PROFILED:
        lines = debug.read_text().splitlines()
        if lines[0] != "KR" or lines[-1] != "E":
            raise RuntimeError(f"Bad profile stream: {lines}")
        ticks, nanos = map(int, lines[1].split())
        stamps = [int(line.split()[1]) for line in lines[2:-1] if not line.startswith("D ")]
        if len(stamps) != len(PHASES) or any(b < a for a, b in zip(stamps, stamps[1:])):
            raise RuntimeError("Incomplete or nonmonotonic timestamps")
        result["tsc_hz_from_hpet"] = ticks * 1e9 / nanos
        result["kernel_ms"] = (stamps[-1] - stamps[0]) * nanos / ticks / 1e6
        result["phases_ms"] = {PHASES[i]: (stamps[i] - stamps[i - 1]) * nanos / ticks / 1e6
                               for i in range(1, len(stamps))}
        result["raw_timestamps"] = stamps
        result["clock_calibration"] = {"tsc_delta": ticks, "hpet_ns_delta": nanos}
        result["details"] = {}
        for line in lines[2:-1]:
            if line.startswith("D "):
                _, index, elapsed, count, size = line.split()
                result["details"][DETAILS[int(index)]] = {
                    "ms": int(elapsed) * nanos / ticks / 1e6, "count": int(count), "bytes": int(size)}
    (run_dir / "result.json").write_text(json.dumps(result, indent=2) + "\n")
    print(f"{variant} {trial}: host prompt {observed['prompt']:.3f} ms"
          + (f", kernel {result['kernel_ms']:.3f} ms" if "kernel_ms" in result else ""), flush=True)
    return result


def verify():
    work = OUT / "verification"
    work.mkdir(exist_ok=True)
    for directory in ("tools", "tests", "boot"):
        shutil.copytree(ROOT / directory, work / directory, dirs_exist_ok=True)
    (work / "kernel").mkdir(exist_ok=True)
    src = work / "kernel/src"
    if src.is_symlink():
        src.unlink()
    shutil.copytree(OUT / "widezero-stock/src", src, dirs_exist_ok=True)
    launcher = work / "compiler/build/install/dukec/bin/dukec"
    launcher.parent.mkdir(parents=True, exist_ok=True)
    launcher.write_text("#!/bin/sh\nexec " + shlex.quote(str(JDK / "java")) + " -cp "
                        + shlex.quote(str(OUT / "widezero-stock/compiler"))
                        + ' duke.compiler.Main "$@"\n')
    launcher.chmod(0o755)
    (work / "build/limine").mkdir(parents=True, exist_ok=True)
    shutil.copy2(OUT / "BOOTX64.EFI", work / "build/limine/BOOTX64.EFI")
    env = os.environ.copy()
    env["PATH"] = str(JDK) + ":" + str(Path(QEMU).parent) + ":" + env["PATH"]
    env["OVMF"] = OVMF
    for name, arguments in [("kernel-tests", ["tools/KernelTests.java"]),
                            ("conformance", ["tools/Conformance.java"]),
                            ("conformance-gc", ["tools/Conformance.java", "--gc-stress", "1"])]:
        print("Running", name, flush=True)
        with (work / (name + ".log")).open("w") as log:
            result = subprocess.run([str(JDK / "java"), *arguments], cwd=work, env=env,
                                    stdout=log, stderr=subprocess.STDOUT)
        print(name, "exit", result.returncode, flush=True)
        if result.returncode:
            print((work / (name + ".log")).read_text()[-6000:], flush=True)
            raise SystemExit(result.returncode)
    print("All probe validation passed", flush=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=["build", "run", "summarize", "verify"])
    parser.add_argument("--variants", nargs="+", choices=["stock", "phases", "detail", "widezero", "widezero-stock"], default=["stock", "phases"])
    parser.add_argument("--trials", type=int, default=7)
    parser.add_argument("--start", type=int, default=1)
    parser.add_argument("--timeout", type=float, default=180)
    args = parser.parse_args()
    if args.action == "build":
        prepare_baseline()
        for variant in args.variants:
            build(variant)
    elif args.action == "run":
        for trial in range(args.start, args.start + args.trials):
            # Alternate order to reduce systematic drift between stock and profiled runs.
            variants = args.variants if trial % 2 else list(reversed(args.variants))
            for variant in variants:
                run(variant, trial, args.timeout)
    elif args.action == "verify":
        verify()
    else:
        results = [json.loads(p.read_text()) for variant in args.variants
                   for p in sorted((OUT / variant).glob("run-*/result.json"))]
        results = [r for r in results if args.start <= r["trial"] < args.start + args.trials]
        qemu_version = subprocess.check_output([QEMU, "--version"], text=True).strip()
        report = {"commit": BASE_COMMIT,
                  "platform": " ".join([os.uname().sysname, os.uname().release, os.uname().machine]),
                  "qemu_version": qemu_version,
                  "scope": f"{qemu_version.splitlines()[0]}, TCG on {os.uname().machine}, q35, 256 MiB, 2 vCPUs, default x86 CPU, 1280x800 framebuffer",
                  "runs": results}
        for variant in args.variants:
            runs = [r for r in results if r["variant"] == variant]
            if not runs:
                continue
            host = [r["host_ms"]["prompt"] for r in runs]
            print(variant, "n=", len(runs), "host median/min/max ms", statistics.median(host), min(host), max(host))
            if variant in PROFILED:
                print("kernel median ms", statistics.median(r["kernel_ms"] for r in runs))
                for phase in sorted(PHASES[1:], key=lambda p: -statistics.median(r["phases_ms"][p] for r in runs)):
                    duration = statistics.median(r["phases_ms"][phase] for r in runs)
                    fraction = statistics.median(r["phases_ms"][phase] / r["kernel_ms"] for r in runs)
                    print(f"{phase:36s} {duration:10.3f} ms {fraction * 100:7.2f}% max speedup {1 / (1 - fraction):.3f}x")
                for name in DETAILS:
                    if not any(r.get("details", {}).get(name, {}).get("count", 0) for r in runs):
                        continue
                    values = [r["details"][name] for r in runs]
                    print(name, "median ms", statistics.median(v["ms"] for v in values),
                          "count", values[0]["count"], "bytes", values[0]["bytes"])
        (ROOT / "artifacts/boot-profile/results.json").write_text(json.dumps(report, indent=2) + "\n")


if __name__ == "__main__":
    main()
