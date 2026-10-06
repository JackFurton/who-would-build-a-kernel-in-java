#!/usr/bin/env bash
# Boots an ESP directory under UEFI. Extra arguments go straight to QEMU.
set -euo pipefail
esp=$1
shift
: "${OVMF:?set OVMF to an x86_64 UEFI firmware image (edk2-x86_64-code.fd / OVMF_CODE.fd)}"
# snapshot=on: the firmware's writes (its variables, as NvVars) go to a throwaway overlay. With
# fat:rw they landed in the directory, and on CI the next boot of it crashed inside Limine.

exec qemu-system-x86_64 \
    -M q35 -m 256M -smp 4 \
    -display none -no-reboot \
    -drive if=pflash,format=raw,readonly=on,file="$OVMF" \
    -drive format=raw,snapshot=on,file=fat:"$esp" \
    "$@"
