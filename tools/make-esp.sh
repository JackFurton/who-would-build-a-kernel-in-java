#!/usr/bin/env bash
# Lays out a UEFI system partition directory: Limine, its config, and the kernel.
set -euo pipefail
esp=$1 limine_efi=$2 config=$3 kernel=$4

rm -rf "$esp"
mkdir -p "$esp/EFI/BOOT" "$esp/boot/limine"
cp "$limine_efi" "$esp/EFI/BOOT/BOOTX64.EFI"
cp "$config" "$esp/boot/limine/limine.conf"
cp "$kernel" "$esp/boot/kernel.elf"
