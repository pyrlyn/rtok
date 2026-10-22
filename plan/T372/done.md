From the Empryo study (idea-only, clean-room). `affected_from_paths` found tests only through symbol references, so a test that never imports the changed file was missed.

Done: before the symbol walk, `affected_from_paths` builds a `HashSet` of indexed paths from `symbol_stats` and unions name-convention candidates for each changed path: `foo.rs` → `tests/foo.rs` / `{dir}/foo_test.rs`; `foo.ts`/`js` → `{dir}/foo.test.*` / `foo.spec.*` / `{dir}/__tests__/foo.*`; `foo.py` → `test_foo.py` / `foo_test.py`; `foo.go` → `foo_test.go`. Matches are marked `← via (by name)`. `is_test_path` also treats `.test.`, `.spec.`, and `/__tests__/` as tests.

Check: `affected_links_tests_by_naming_convention` (Rust name link + negative + candidate table for four languages); existing affected tests unchanged; `cargo test --lib affected_links_tests_by_naming_convention` green.

Status: done 2026-10-06
Model: Grok Bot
