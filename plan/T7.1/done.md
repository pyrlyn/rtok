**T7.1 modes as data** · T2.4 · `modes/terse.md`, `modes/yagni.md`, `src/plugins/inject.rs`
Do: copy the intent of caveman (terse output) and ponytail (YAGNI ladder) into ≤ 250-token markdown files under `~/.rtok/modes/`; `rtok setup --mode terse,yagni` enables; injected once per session via `inject` (priority 5), not per prompt.
Check: `rtok hook SessionStart` output contains the mode text once; UserPromptSubmit output does not.
Status: done 2026-09-02 · Check: SessionStart additionalContext contains `# terse` and `# yagni` once; UserPromptSubmit does not. Files ≤ 250 tokens. `make check` green. Deviation: `--mode` on `setup` maps to `setup.modes`; builtins via `include_str!`.
