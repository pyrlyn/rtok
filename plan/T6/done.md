# T6. Done

## What was done

- `docs/binary-size.md`: composition of the 108,768,408-byte release binary, comparison with the Rust rtok
  (46,088,256 bytes), per-flag effect, the two reductions applied, remaining proposals, and every command that
  produced a number.
- `scripts/size_check.py` and the `size-check` mise task: link the release binary, exit 1 when it is over the
  budget (79,000,000 bytes for macosArm64, agreed with the creator: measured size + 2.3%).
- `scripts/size_map.py`: reads an ld64 linker map and attributes bytes to Kotlin packages, C libraries and
  individual tree-sitter grammars. `-Prtok.linkMap=<file>` in `rtok/build.gradle.kts` makes the link write the map.
- **Binary 108,768,408 → 77,245,112 bytes (−31.5 MB, −29.0%)**, by two changes the creator approved:
  1. The Compose web UI wasm modules are embedded as a zstd -19 frame (base64 text) instead of raw base64:
     −11.1 MB. `buildSrc` compresses with `com.github.luben:zstd-jni` 1.5.7-25; `rtok-web` decodes with the
     vendored C zstd on first request (64 MiB cap).
  2. The `agda` and `verilog` tree-sitter grammars are removed (`grammars.json`, `Languages.kt`; `grammars.py fetch`
     now prunes unpinned vendor directories): −20.4 MB. `read mode=stripped` on `.v`, `.sv`, `.agda` falls back to `full`.

## Findings

- 67% of the original binary was tree-sitter parse tables (≈73 MB). 20 of the 34 grammars were parse-only (stripped
  mode, 49.9 MB); the Rust rtok has 13 grammars. Those 20 were added by the port (Phase 10), not by the Rust rtok.
- 15% (≈16.2 MB) was the Compose web UI: no Compose code is linked, but `embedDir` stored its two wasm modules
  (11.8 MB raw: 8.6 MB Skiko/GL and 3.2 MB Kotlin/Wasm) as base64 strings.
- Code is only 16 MB (Rust: 28.5 MB).
- The numbers in `docs/build-speed.md` ("Release binary size", 87.6 MB) are stale; T3 owns the docs layout, so it was not edited.

## Left for the creator (details in `docs/binary-size.md`)

- Drop ocaml ×3, scala, haskell, julia as well: −23.3 MB more. Or load all remaining parse-only grammars on demand: up to −29.6 MB.
- Store the wasm frame as raw bytes instead of base64: about −1.2 MB.
- `docs/tree-sitter.md` keeps its 2026-10-26 per-grammar sizes (marked as predating the removal).
- `toolchain.md`: add a row for `com.github.luben:zstd-jni` (buildSrc, compression of embedded assets) once T3's
  toolchain move (#254) and T2's `docs/toolchain.md` edits (#255) have merged; it is not added here so that neither
  PR conflicts.
- `:rtok-e2e:detekt` currently fails on a magic number in `GuardCheckE2eSpec.kt:61` (from #250; #253 touches that file).

## Checked

- `python3 scripts/size_check.py` (what `mise run size-check` runs) links the release binary and, after the changes,
  prints `77,245,112 B, budget 79,000,000 B, headroom 1,754,888 B`, exit 0. `--no-link --budget 100000000` on the old
  108 MB binary exits 1; no budget configured exits 2.
- `env -u RTOK_AGENT_ID ./gradlew macosArm64Test cliTest e2eTest -x :rtok-e2e:detekt --continue`: BUILD SUCCESSFUL
  (includes ktlint, detekt of the other modules, `EmbeddedAssetsTest` with a real zstd frame and a corrupt frame, and
  the e2e suite). `NativeTreeSitterTest` now expects at least 32 grammars (its detekt baseline entry was renamed).
- Without `env -u RTOK_AGENT_ID`, `AgentsCliSpec` fails because the agent session id of the running session is in the
  environment; unrelated to this task.
- `size_map.py` was run on the map of the unstripped link; the wasm modules were decoded from that binary.

## Wiring `size-check` into CI (once T1 has merged)

Add a job (or a step after the macOS arm64 release link) that runs on `macos-latest` (arm64):

```yaml
- run: mise run size-check          # links the release binary, then compares with the budget
```

If the job already links the release binary in an earlier step, run `python3 scripts/size_check.py --no-link`
to avoid a second link; the default path is `rtok/build/bin/macosArm64/releaseExecutable/rtok.kexe`, or pass
`--binary`. The link takes 2-4 minutes. The budget lives in `BUDGETS` in `scripts/size_check.py`; raise it in the
same PR that adds the bytes, with the reason in the commit message. Do not wire it to `release-fast` builds: their
binary is 22% larger by design (`docs/experiments/binary-split.md`).
