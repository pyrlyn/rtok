Best competitor: lean-ctx gh.rs. Design: `gh pr/issue view`: drop empty fields, body markdown whitespace collapse; `gh run view --log`: per-step errors only; `gh pr checks`: only non-pass rows + counts; `--json` → TOON.

Check: #1076 harness (`bench/c14`) shows +20 pp vs current head/tail on this category with full anchor retention.

Plan: `gh` formatter in `compact.rs`: `pr/issue view` drops empty fields and collapses markdown whitespace; `pr checks` keeps non-pass rows plus counts; `run view --log` keeps per-step error blocks; `--json` goes through the TOON helper if present. Tests: golden files per subcommand with `min_saving:`; failing check rows and URLs kept.

Result: Measured by golden bytes (the #1076 bench/c14 harness is not on main): `gh pr checks` 24 rows 2064 B -> 218 B (89%, counts plus the failing and pending rows with their URLs), `gh run view --log` 5720 B -> 187 B (97%, the failing step with 2 lines before and 4 after each error/warning line, timestamps off, quiet steps counted), `gh pr view` 107 -> 92 tokens (14%; empty fields and blank runs go, the body is kept). New src/plugins/cmd/gh.rs with a gh arm in formatters.rs; any other shape (--json, --comments, gh pr list) and any output that would not be shorter falls back to the gh rule. Not done: --json to TOON (the helper from #1078 is not on main); follow-up once it lands.

Status: done 2026-10-15
Model: Claude Code / claude-sonnet-5-5
