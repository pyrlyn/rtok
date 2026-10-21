**T17.1 dev, release and dist profiles** · — · `Cargo.toml`, `research.md`
Do: `[profile.dev] debug = "line-tables-only"` (backtraces keep `file:line`; the DWARF that dominates every artifact goes). `[profile.dev.package.lbug] opt-level = 2, debug = false` so `cmake-rs` reads `OPT_LEVEL`/`DEBUG` and configures the bundled C++ as a release build instead of a 2 GB `-g` one. `[profile.release] strip = "symbols"`. `[profile.dist]` gains `codegen-units = 1` beside its `lto = "thin"`, since the shipped binary is built once. Nothing sets `panic = "abort"`.
Check: `cargo clean -p lbug -p rtok`, then a cold `cargo build` and `cargo build --release`; both binaries and `liblbug.a` shrink and the numbers with a date go into `research.md` §2; `just check` green; a deliberate plugin panic still prints `file:line`.
Status: done 2026-09-04
Model: Opus 5
Check result: `cargo clean -p lbug -p rtok` freed 24.5 GiB across 34 028 files before the cold
builds. Release `rtok` 22 374 576 → 19 157 712 B (−14.4 %), build time unchanged at 1m30s.
`liblbug.a` 2 169 748 672 → 83 940 608 B (−96.1 %, 25.8×) and the `lbug` cmake directory 4.3 GiB
→ 358 MiB (−92 %), at a cold-build cost of 3m36s → 8m36s: `-O3` for five minutes, once per
feature set. The dev binary needed an isolated A/B to measure honestly — the `target/debug/rtok`
lying in the shared target dir was 45 135 464 B from an older feature set, which made the new
binary look 12 MB larger. Three cold builds into three empty target dirs, `--config
profile.dev.debug=…`: `true` 62 500 520 B / 1 479 MiB, `line-tables-only` 60 378 600 B / 1 211 MiB,
`false` 54 364 592 B / 945 MiB. `just check` green (exit 0, 12 test binaries ok).
Panic check, run rather than assumed: a crate compiled with exactly `-Cdebuginfo=line-tables-only`
still returns `Err` from `catch_unwind`, and `RUST_BACKTRACE=1` prints `panicked at
src/lib.rs:1:14` with `at ./src/lib.rs:1:14`, `:2:14`, `:6:18`, `:5:42` on the frames.
Gate P17's p95 clause is the one thing not cleanly passed: `rtok hook PostToolUse` measured
p95 10.07 ms stripped against 9.83 ms unstripped, interleaved run-for-run on the same machine
(repeat rounds 9.83–10.91 ms), so it sits on the 10 ms bar rather than under it. The A/B places
that outside this change — the unstripped binary of the same commit drifted with it, and stripping
is marginally *faster* at the median (8.16 vs 8.33 ms). The drift is the machine or the grown
`rtok.db` against the 8.89 ms the same harness recorded at Gate P16 earlier the same day.
Also answered here (user question, no code): packaging `liblbug` as a `.framework` saves nothing
(same Mach-O in a directory), an `.xcframework` is larger by construction and has no slice for the
Linux x86_64 target `dist` ships, and a `.dylib` — the only variant that would remove bytes, since
`+whole-archive` copies lbug into `rtok` and into each of ~12 test binaries — costs D1's single
static binary, has no `-rpath` from `lbug`'s `build.rs`, and cannot differ between dev and release
because `LBUG_SHARED` is a build-time env var and `.cargo/config.toml [env]` is global.
