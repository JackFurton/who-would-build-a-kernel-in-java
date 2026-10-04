#!/usr/bin/env bash
# Boots an ESP directory under UEFI. Extra arguments go straight to QEMU.
set -euo pipefail
esp=$1
shift
: "${OVMF:?set OVMF to an x86_64 UEFI firmware image (edk2-x86_64-code.fd / OVMF_CODE.fd)}"

exec qemu-system-x86_64 \
    -M q35 -m 256M -smp 2 \
    -display none -no-reboot \
    -drive if=pflash,format=raw,readonly=on,file="$OVMF" \
    -drive format=raw,file=fat:rw:"$esp" \
    "$@"
