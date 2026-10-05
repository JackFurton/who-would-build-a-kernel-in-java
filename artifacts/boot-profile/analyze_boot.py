#!/usr/bin/env python3
"""Summarize matched trials and export an Amdahl figure (requires matplotlib)."""

import json
import os
from pathlib import Path
import random
import statistics

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
BUILD = ROOT / "build/boot-profile"
os.environ.setdefault("MPLCONFIGDIR", str(BUILD / "matplotlib"))
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np

VARIANTS = ["stock", "detail", "widezero", "widezero-stock"]
RECORDED = json.loads((HERE / "results.json").read_text())
TRIALS = sorted(set.intersection(*[
    {r["trial"] for r in RECORDED["runs"] if r["variant"] == v} for v in VARIANTS]))
RUNS = {v: sorted([r for r in RECORDED["runs"] if r["variant"] == v and r["trial"] in TRIALS],
                  key=lambda r: r["trial"]) for v in VARIANTS}
median = statistics.median


def distribution(values):
    return {"median": median(values), "min": min(values), "max": max(values), "values": values}


boot_before = [r["host_ms"]["prompt"] for r in RUNS["stock"]]
boot_after = [r["host_ms"]["prompt"] for r in RUNS["widezero-stock"]]
kernel_before = [r["kernel_ms"] for r in RUNS["detail"]]
kernel_after = [r["kernel_ms"] for r in RUNS["widezero"]]
zero_before = [r["details"]["gc_tail_zero_nested_in_sweep"]["ms"] for r in RUNS["detail"]]
zero_after = [r["details"]["gc_tail_zero_nested_in_sweep"]["ms"] for r in RUNS["widezero"]]
b, a, k, n, z, w = map(median, [boot_before, boot_after, kernel_before, kernel_after, zero_before, zero_after])
local_speedup = z / w
f_boot, f_kernel = z / b, z / k
predict = lambda f, speed: 1 / (1 - f + f / speed)

data = {
    "commit": RECORDED["commit"],
    "scope": RECORDED["scope"],
    "workload": "Unchanged boot to first shell prompt, including 512 MiB allocation demo and timer sleep",
    "reason": f"Reclaimed heap-tail clearing takes {f_kernel:.1%} of kernel startup and {f_boot:.1%} of full boot on the measured setup.",
    "hypothesis": f"Replacing REP STOSB with REP STOSQ for the already word-aligned heap tail reduces the repeated stores in TCG while zeroing exactly the same bytes. Expected local gain: about 8x. Amdahl projection at 8x: about {predict(f_kernel, 8):.2f}x kernel startup and {predict(f_boot, 8):.2f}x full boot.",
    "proposal": "perf: take out the garbage eight bytes at a time",
    "reproduce": {
        "prerequisites": ["JDK 25", "QEMU with x86_64 UEFI firmware", "Python 3, make, git, tar"],
        "limine_revision": "5be26a73d7b7b4d4477d18be94e1d16e615adf56",
        "limine_sha256": "333f7a69379b1f47e019be215885cdc078b9edd6e9255cbe2f706d6c5ec0ef06",
        "override_variables": ["DUKE_PROFILE_JDK (bin directory)", "DUKE_PROFILE_QEMU", "OVMF"],
        "commands": [
            "python3 -B artifacts/boot-profile/profile_boot.py build --variants stock detail widezero widezero-stock",
            "python3 -B artifacts/boot-profile/profile_boot.py run --variants stock detail widezero widezero-stock --trials 7 --start 15",
            "python3 -B artifacts/boot-profile/profile_boot.py verify",
            "python3 -B artifacts/boot-profile/profile_boot.py summarize --variants stock detail widezero widezero-stock --trials 7 --start 15",
            "python3 -B artifacts/boot-profile/analyze_boot.py (requires numpy and matplotlib)",
        ],
    },
    "trials": TRIALS,
    "method": [
        "Fresh QEMU process per boot; alternate variant order across trials; no concurrent benchmark processes.",
        "Host filesystem caches were not flushed; these are fresh VM boots, not cold host-cache measurements.",
        "Full-boot wall time: Python monotonic clock at process launch to first complete serial prompt.",
        "Kernel/phase timing: buffered guest RDTSC deltas, scaled against HPET over the existing sleep interval.",
        "Profile reporting happens on debugcon only after the first rendered shell prompt.",
        "Whole-boot A/B uses uninstrumented images; kernel A/B uses identically instrumented images.",
        "Before-kernel duration in the plot is derived by subtracting profiled kernel median from uninstrumented full-boot median.",
        "This measures emulator performance, including translation and device effects; it is not native x86 benchmarking.",
        "The baseline compiler varies build-time array placement between builds; recorded hashes identify the measured images.",
    ],
    "boot_before_ms": distribution(boot_before), "boot_after_ms": distribution(boot_after),
    "kernel_before_ms": distribution(kernel_before), "kernel_after_ms": distribution(kernel_after),
    "zero_before_ms": distribution(zero_before), "zero_after_ms": distribution(zero_after),
    "zero_calls": [r["details"]["gc_tail_zero_nested_in_sweep"]["count"] for r in RUNS["detail"]],
    "zero_bytes": [r["details"]["gc_tail_zero_nested_in_sweep"]["bytes"] for r in RUNS["detail"]],
    "zero_speedup": local_speedup,
    "f_boot": f_boot, "f_kernel": f_kernel,
    "max_boot_speedup_if_zero_free": 1 / (1 - f_boot),
    "max_kernel_speedup_if_zero_free": 1 / (1 - f_kernel),
    "predicted_boot_speedup": predict(f_boot, local_speedup),
    "observed_boot_speedup": b / a,
    "boot_latency_reduction_percent": (1 - a / b) * 100,
    "predicted_kernel_speedup": predict(f_kernel, local_speedup),
    "observed_kernel_speedup": k / n,
    "baseline_phase_medians_ms": {p: median(r["phases_ms"][p] for r in RUNS["detail"])
                                  for p in RUNS["detail"][0]["phases_ms"]},
    "baseline_nested_medians_ms": {p: median(r["details"][p]["ms"] for r in RUNS["detail"])
                                   for p in RUNS["detail"][0]["details"]},
    "instrumentation_wall_medians_ms": {
        v: median(r["host_ms"]["prompt"] for r in RUNS[v]) for v in VARIANTS},
    "validation": json.loads((HERE / "validation.json").read_text()),
}
rng = random.Random(20261005)
samples = []
for _ in range(10000):
    indices = [rng.randrange(len(TRIALS)) for _ in TRIALS]
    samples.append(median(boot_before[i] for i in indices) / median(boot_after[i] for i in indices))
samples.sort()
data["paired_bootstrap_boot_speedup_95pct"] = [samples[250], samples[9749]]

data["image_sha256"] = {v: sorted({r["image_sha256"] for r in RUNS[v]}) for v in VARIANTS}
(HERE / "summary.json").write_text(json.dumps(data, indent=2) + "\n")

plt.rcParams.update({"font.family": "DejaVu Sans", "font.size": 10})
fig, (left, right) = plt.subplots(1, 2, figsize=(13.2, 6), gridspec_kw={"width_ratios": [1.2, 1]})
fig.suptitle(f"Boot profile: a {local_speedup:.1f}× local speedup becomes a {b/a:.2f}× full-boot speedup", fontsize=16, weight="bold", y=.98)
colors = ["#8898aa", "#ed7046", "#4479ac"]
labels = ["Before kernel / host overhead (derived)", "Reclaimed-heap clearing", "Remaining kernel startup"]
positions = [1, 0]
offset = np.zeros(2)
parts = [[(b - k) / 1000, (a - n) / 1000], [z / 1000, w / 1000], [(k - z) / 1000, (n - w) / 1000]]
for values, color, label in zip(parts, colors, labels):
    left.barh(positions, values, left=offset, color=color, height=.45, label=label)
    offset += values
for y, value in zip(positions, [b / 1000, a / 1000]):
    left.text(value + .035, y, f"{value:.3f} s", va="center", weight="bold")
left.set_yticks(positions, ["Stock", "Wide-zero probe"])
left.set_xlim(0, 2.9)
left.set_ylim(-.6, 1.7)
left.set_xlabel("QEMU launch → first shell prompt (seconds)")
left.set_title(f"Same boot workload; {len(TRIALS)} interleaved trials", loc="left", pad=12)
left.legend(loc="upper left", bbox_to_anchor=(0, -.23), frameon=False, fontsize=9)
left.spines[["top", "right", "left"]].set_visible(False)
left.tick_params(axis="y", length=0)

x = np.geomspace(1, 64, 300)
right.plot(x, predict(f_kernel, x), color="#4479ac", label=f"Kernel only: f = {f_kernel:.1%}", linewidth=2.3)
right.plot(x, predict(f_boot, x), color="#ed7046", label=f"Full boot: f = {f_boot:.1%}", linewidth=2.3)
right.scatter([local_speedup, local_speedup], [k / n, b / a], c=["#4479ac", "#ed7046"], s=65, zorder=5)
right.annotate(f"Observed {k/n:.2f}×", (local_speedup, k/n), xytext=(10, -16), textcoords="offset points", color="#285379")
right.annotate(f"Observed {b/a:.2f}×", (local_speedup, b/a), xytext=(10, 10), textcoords="offset points", color="#a53f20")
right.axhline(1 / (1 - f_boot), color="#ed7046", alpha=.4, linestyle=":")
right.set_xscale("log", base=2)
right.set_xticks([1, 2, 4, 8, 16, 32, 64], ["1×", "2×", "4×", "8×", "16×", "32×", "64×"])
right.set_ylim(.95, 3.55)
right.set_xlabel("Speedup of reclaimed-heap clearing")
right.set_ylabel("Overall speedup")
right.set_title("Amdahl: 1 / ((1 − f) + f / s)", loc="left", pad=12)
right.legend(frameon=False, loc="upper left", fontsize=9)
right.spines[["top", "right"]].set_visible(False)
right.grid(axis="y", alpha=.15)
fig.text(.02, .025, f"Duke {data['commit'][:7]} · {RECORDED['qemu_version'].splitlines()[0]} · {RECORDED['platform']} · TCG · Native x86 performance unmeasured", fontsize=9, color="#536272")
fig.subplots_adjust(top=.82, bottom=.34, left=.115, right=.97, wspace=.32)
fig.savefig(HERE / "boot-profile.svg", facecolor="white")
fig.savefig(HERE / "boot-profile.png", facecolor="white", dpi=160)
print(json.dumps({key: data[key] for key in ["observed_boot_speedup", "boot_latency_reduction_percent", "observed_kernel_speedup", "zero_speedup", "f_boot", "f_kernel", "paired_bootstrap_boot_speedup_95pct"]}, indent=2))
