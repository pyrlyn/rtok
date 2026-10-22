Five goldens carried floors of 0–3 % (`cat`, `git_log`, `npm`, `make`, `mvn`) because their inputs were too short to save anything, and the `[script]` mixed-chain rule had no golden with a floor.

Result: long inputs next to the short ones (which stay, `cat.in` keeps its secret-preservation role): `cat_long` (measured 79 %, floor 74), `git_log_long` (75 → 70), `npm_install` (57 → 52), `make_long` (62 → 57), `mvn_long` (83 → 78), and `script` (`npm run build && pytest -q`, 45 → 40). The golden harness's `argv:` header now takes a single-quoted command as one argv element, the shape the hook sends (`rtok run -- '<cmd>'`), which is the only way to reach the mixed-chain routing; unquoted headers split as before.

Check: `cargo nextest run -p rtok --lib cmd::formatters` 16/16; `cargo clippy -p rtok --lib --tests -- -D warnings` clean.

Status: done 2026-09-27
Model: Claude Code / claude-sonnet-5 (code), claude-opus-5-5 (review)
