From the Empryo study (idea-only). `rrf_merge` sorted by score alone, so equal RRF scores (common when a note ranks in only one of the FTS/KNN lists) could reorder between runs and break a cache-stable recall prefix.

Done: sort by `(score desc, id asc)`. Unit test builds two single-hit lists with equal scores and asserts the same id order across 50 runs.

Check: `rrf_merge_breaks_score_ties_by_note_id` in `src/store/embed.rs`; `cargo test --lib rrf_merge_breaks_score_ties_by_note_id` green.

Status: done 2026-10-06
Model: Grok Bot
