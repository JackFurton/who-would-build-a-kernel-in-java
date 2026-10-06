#!/usr/bin/env bash
# usage: tools/boot-loop.sh [runs] [fresh]
# Boots the ESP over and over and reports how often the firmware or kernel dies, to put a number on a
# flaky boot. With "fresh" the ESP directory is copied anew for every run; otherwise it is reused.
# Logs of failed boots are kept in build/boot-loop/.
set -uo pipefail
cd "$(dirname "$0")/.."
runs=${1:-20} mode=${2:-reuse}
out=build/boot-loop
rm -rf "$out" && mkdir -p "$out"
fail=0
for ((i = 1; i <= runs; i++)); do
    esp=build/esp
    if [[ $mode == fresh ]]; then
        esp=$out/esp
        rm -rf "$esp" && cp -R build/esp "$esp"
    fi
    if ! tools/boot-test.sh "$esp" "$out/run-$i.log" DUKE-BOOT-OK 60 > "$out/run-$i.txt" 2>&1; then
        fail=$((fail + 1))
        echo "run $i: FAIL ($(tail -1 "$out/run-$i.txt"))"
    else
        rm -f "$out/run-$i.log" "$out/run-$i.txt"
    fi
done
echo "boot-loop ($mode): $fail/$runs failed"
