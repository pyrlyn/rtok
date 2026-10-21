**T1.3 baseline snapshot** · T1.2 · `src/measure/baseline.rs`
Do: `rtok stats --save-baseline <name>` stores the report JSON in `measurements`; `rtok stats --compare <name>` prints deltas.
Check: save, then compare → all deltas 0.
Status: done 2026-09-02 · Check: save then compare → all Δ0 (`cargo test measure::baseline`; CLI `--save-baseline before-rtok` then `--compare before-rtok`).
