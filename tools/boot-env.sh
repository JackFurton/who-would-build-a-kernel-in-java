#!/usr/bin/env bash
# Prints what a boot depends on, so a failing run can be compared with a passing one.
cd "$(dirname "$0")/.."
echo '### Boot environment'
echo '```'
echo "qemu:    $(qemu-system-x86_64 --version | head -1)"
echo "ovmf:    ${OVMF:-unset} ($(dpkg-query -W -f='${Version}' ovmf 2>/dev/null || echo unknown))"
echo "cpu:     $(grep -m1 'model name' /proc/cpuinfo 2>/dev/null | cut -d: -f2- | xargs)"
echo "kvm:     $([ -w /dev/kvm ] && echo available || echo none)"
echo "runner:  $(uname -sr)"
[ -f build/kernel.elf ] && echo "kernel:  $(stat -c %s build/kernel.elf) bytes, sha256 $(sha256sum build/kernel.elf | cut -c1-16)"
[ -f build/limine/BOOTX64.EFI ] && echo "limine:  $(stat -c %s build/limine/BOOTX64.EFI) bytes, sha256 $(sha256sum build/limine/BOOTX64.EFI | cut -c1-16)"
echo '```'
