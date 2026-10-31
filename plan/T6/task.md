---
status: in progress
priority: P2
complexity: 3
readiness: 100%
agent: Claude Code / Sonnet 5.5
---

# T6. Binary size and budget

`rtok/build/bin/macosArm64/releaseExecutable/rtok.kexe` (`./gradlew :rtok:linkReleaseExecutableMacosArm64`) is
108,768,408 bytes; the Rust rtok release binary is far smaller. Nobody knows what the bytes are made of and
nothing stops the size from growing further.

Done means:

- `docs/binary-size.md` explains the composition (Kotlin code per klib, the 34 tree-sitter grammars, bundled
  SQLite, zstd, ktor, Compose / web modules reachable or not), compares with the Rust binary, and carries the
  commands that produced every number plus primary sources for each Kotlin/Native flag it cites.
- `scripts/size_check.py` plus a `size-check` mise task link the release binary and fail when it exceeds a
  budget. The budget is proposed to the creator before it is fixed in the script.
- The doc lists cheap wins (strip, release linker flags, unused grammars) with measured effect. A win is applied
  only if behaviour is unchanged and `./gradlew macosArm64Test cliTest e2eTest` passes; otherwise it stays a
  proposal.
- `plan/T6/done.md` says how to wire `size-check` into CI once T1 merges. `.github/`, `PORTING.md`, `README.md`
  and `plan/README.md` are not touched (T1 and T3 own them).

## Execution plan

1. Link the release binary (`./gradlew :rtok:linkReleaseExecutableMacosArm64`) and record the size and the
   effective linker / compiler arguments (check whether the `diffuser.releaseSize=all` flags documented in
   `docs/build-speed.md` still reach the link after the shared-settings move).
2. Measure composition: `size -m`, `nm -S`/`--size-sort` grouped by symbol prefix (klib package / C library),
   a linker map (`-Wl,-map`) for per-object attribution, and the klib dependency list of `:rtok`.
3. Build the Rust rtok release binary with `cargo build --release` in a scratch target dir (measure only).
4. Write `docs/binary-size.md`.
5. Add `scripts/size_check.py` (style of `scripts/smart.py`) and the `size-check` mise task; ask the creator for
   the budget.
6. Try each cheap win on its own, record size and behaviour; apply a win only under the rule above.
7. Write `plan/T6/done.md`, open the PR.
