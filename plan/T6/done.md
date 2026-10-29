# T6. Done

## What was done

- `docs/binary-size.md`: composition of the 108,768,408-byte release binary, comparison with the Rust rtok
  (46,088,256 bytes), per-flag effect, proposals with their effect, and every command that produced a number.
- `scripts/size_check.py` and the `size-check` mise task: link the release binary, exit 1 when it is over the
  budget (111,000,000 bytes for macosArm64, agreed with the creator: measured size + 2.1%).
- `scripts/size_map.py`: reads an ld64 linker map and attributes bytes to Kotlin packages, C libraries and
  individual tree-sitter grammars. `-Prtok.linkMap=<file>` in `rtok/build.gradle.kts` makes the link write the map.
- No size reduction was applied. The release flags (`small`, `strip`, `latin1`) were already on; the remaining wins
  change the build or behaviour and are proposals for the creator (below).

## Findings

- 67% of the binary is tree-sitter parse tables (≈73 MB). 20 of the 34 grammars are parse-only (stripped mode),
  49.9 MB; `verilog` alone is 18.1 MB. The Rust rtok has 13 grammars.
- 15% (≈16.2 MB) is the Compose web UI: no Compose code is linked, but `embedDir` stores its two wasm modules
  (11.8 MB raw: 8.6 MB Skiko/GL and 3.2 MB Kotlin/Wasm) as base64 strings. Compressed with zstd they would be 3.6 MB.
- Code is only 16 MB (Rust: 28.5 MB). Compressed the Kotlin binary is 12% larger than the Rust one (gzip 18.6 vs 16.7 MB).
- The numbers in `docs/build-speed.md` ("Release binary size", 87.6 MB) are stale; T3 owns the docs layout, so it was not edited.

## Proposals, in order of value

1. Embed the wasm modules as zstd-compressed bytes instead of base64 text: about −12.6 MB.
2. Drop `verilog` (or all 20 parse-only grammars, up to −49.9 MB, as on-demand plugins): −18.1 MB for verilog.
Both need the creator's decision; details and the evidence are in `docs/binary-size.md`.

## Checked

- `python3 scripts/size_check.py` (what `mise run size-check` runs) linked the release binary and printed
  `108,768,408 B, budget 111,000,000 B, headroom 2,231,592 B`, exit 0; the size is identical to the one reported
  at the start. `--no-link --budget 100000000` exits 1, no budget configured exits 2.
- `size_map.py` was run on the map of the unstripped link; the wasm modules were decoded from that binary.
- No tests were run: nothing that changes the binary or behaviour was applied (the only build change is a
  `linkerOpts` line that does nothing unless `-Prtok.linkMap` is given).

## Wiring `size-check` into CI (once T1 has merged)

Add a job (or a step after the macOS arm64 release link) that runs on `macos-latest` (arm64):

```yaml
- run: mise run size-check          # links the release binary, then compares with the budget
```

If the job already links the release binary in an earlier step, run `python3 scripts/size_check.py --no-link`
to avoid a second link; the default path is `rtok/build/bin/macosArm64/releaseExecutable/rtok.kexe`, or pass
`--binary`. The link takes 2-3 minutes. The budget lives in `BUDGETS` in `scripts/size_check.py`; raise it in the
same PR that adds the bytes, with the reason in the commit message. Do not wire it to `release-fast` builds: their
binary is 22% larger by design (`docs/experiments/binary-split.md`).
