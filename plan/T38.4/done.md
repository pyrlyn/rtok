**T38.4 drop stale `divan`/`lbug` rows from `toolchain.md`** · P3, 1/5 · `toolchain.md`
Do: delete the `divan` and `lbug` rows — neither crate is in any manifest (`lbug` removed by P39; only historical prose in `src/plugins/graph/PLAN.md` mentions it, `divan` nowhere). Nothing else changes.
Check: `divan`/`lbug` no longer appear in `toolchain.md`; the diff touches only `toolchain.md`.
Complexity: 1/5
Status: done 2026-09-14 · Model: OpenCode / Muse Spark 1.3
Evidence: `grep 'divan|lbug' toolchain.md` — no matches; neighbouring rows (`dotenvy`, `figment`, `libsqlite3-sys`, `notify`) intact.
Deviation: the task was scoped from a rust.md audit that first misreported `figment`/`rmcp`/`similar`/`indicatif`/`owo-colors` as missing — re-read showed all five present (`rust.md:456,477,478,506,579`), so `rust.md` needed no change and the task shrank to the two stale `toolchain.md` rows.
