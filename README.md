# metrics

Written by CI on every push to main (`tools/record-metrics.sh`): one row per commit in
`metrics.csv`, and the chart the main README embeds. Suite sizes count tests; main only moves
through green CI, so every one of them passes. `text_bytes` is the boot kernel's `.text` after
tree-shaking, so it grows with what `Kernel.main` reaches, not with the size of the library.
