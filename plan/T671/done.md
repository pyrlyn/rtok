MemPalace is ahead on admission/dedup by embeddings (`docs/comparison/rtok-vs-mempalace.md`). Add an admission gate before notes are stored (cheap hash/trigram first; embeddings opt-in). Savings both sides: **no data** (harness skips).

Check: duplicate/low-value notes rejected in tests; default path needs no network; `just check`.

**Source audit (T530).**

T531–T539 are confirmed source defects on `0b14b88e`, filed for implementation rather
than fixed during this audit. Start with the incorrect-response cases T532/T533, then
the remaining independent P2 fixes. Each card distinguishes executed function probes
from source-derived reproduction steps and gives its own implementation/check sequence.
Existing tasks, including uncommitted T522–T527 in the shared checkout, were checked for
overlap and were not copied or reassigned. This is a bounded review of the core data
paths, not a claim that every bug or host/platform combination has been exhausted.

**Plan.** (1) New `src/plugins/memory/admit.rs`: normalize (lowercase, collapse whitespace), exact-hash and trigram-Jaccard check against the notes of the same project (newest 200), reject empty/too-short bodies and near-duplicates (>= 0.9) with the existing note id in the reply; a same project+kind+title save still updates in place. (2) Wire into `mem_save` and the `remember:` path only (never a hook, never network); thresholds under `plugins.memory.admit` (`enabled = true`, `min_body_chars`, `dup_similarity`) in the one config module. (3) Embedding similarity stays opt-in: used only when `embed.enabled`. (4) Fail-first tests: exact dup, whitespace/case dup, near-dup, distinct note admitted, short body rejected, gate off; `just check`.

**Result (2026-10-15).** PR #1090. `mem_save` now runs an admission gate (`src/plugins/memory/admit.rs`): a body shorter than `plugins.memory.admit_min_chars` (5) is refused, and so is a body whose normalized character-trigram Jaccard similarity to one of the project's newest 200 live notes reaches `admit_similarity` (85 %); the refusal names the stored note (`{"id", "updated": false, "refused": "duplicate"|"too_short"}` over MCP). A same project+kind+title save still updates in place. `admit = false` turns the gate off. The keys live flat in `[plugins.memory]` rather than in a new `admit` section. Not done: the `remember:` prompt path is not gated (it never calls `mem_save`), and the opt-in embedding-similarity leg was left out.
