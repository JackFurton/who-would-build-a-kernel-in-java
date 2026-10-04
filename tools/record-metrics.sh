#!/usr/bin/env bash
# CI, on pushes to main: appends this commit's metrics to the metrics branch and re-renders the
# progress chart the README embeds. Expects the kernel and compiler test results already built.
set -euo pipefail

row=$(java tools/Metrics.java)
commit=$(echo "$row" | cut -d, -f2)
dir=$(mktemp -d)

git fetch -q origin metrics
git worktree add -q "$dir" origin/metrics
trap 'git worktree remove --force "$dir"' EXIT

for attempt in 1 2 3; do
    if ! cut -d, -f2 "$dir/metrics.csv" | grep -qx "$commit"; then
        echo "$row" >> "$dir/metrics.csv"
    fi
    java tools/ProgressChart.java "$dir/metrics.csv" "$dir"
    git -C "$dir" add metrics.csv progress-light.svg progress-dark.svg
    git -C "$dir" -c user.name="github-actions[bot]" -c user.email="41898282+github-actions[bot]@users.noreply.github.com" \
        commit -q -m "Metrics for $commit" || exit 0
    if git -C "$dir" push -q origin HEAD:metrics; then
        exit 0
    fi
    # Another main push recorded first: start again from its version.
    git -C "$dir" fetch -q origin metrics
    git -C "$dir" reset -q --hard origin/metrics
done
exit 1
