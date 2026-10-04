#!/usr/bin/env bash
# Boots headless and passes once MARKER shows up on the serial port. Fails on a kernel panic,
# on QEMU exiting early (a triple fault exits because of -no-reboot), or after TIMEOUT seconds.
set -euo pipefail
esp=$1 log=$2 marker=$3 timeout=${4:-120}

rm -f "$log"
"$(dirname "$0")/qemu.sh" "$esp" -serial file:"$log" &
pid=$!
trap 'kill $pid 2>/dev/null || true' EXIT

result=timeout
for ((i = 0; i < timeout * 10; i++)); do
    if grep -q "$marker" "$log" 2>/dev/null; then
        result=pass
        break
    fi
    if grep -q "PANIC" "$log" 2>/dev/null; then
        result=panic
        break
    fi
    if ! kill -0 $pid 2>/dev/null; then
        result=qemu-exited
        break
    fi
    sleep 0.1
done

# Strip firmware screen-control noise so the log reads cleanly in CI.
tr -d '\r' < "$log" | sed -e 's/\x1b\[[0-9;=]*[A-Za-z]//g' || true
echo "boot-test: $result"
[[ $result == pass ]]
