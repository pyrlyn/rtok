**T18.2 the next patch, computed** · T18.1 · `.github/workflows/bump.yml`, `justfile`
Do: one `workflow_dispatch` entry point that reads the version in `Cargo.toml`, raises the patch (level selectable, `patch` by default), regenerates `CHANGELOG.md` with git-cliff, commits `release: v<next>` with `Cargo.lock` updated, and starts the release for that version. `just release` does the same locally for anyone without Actions access. Neither path takes a version as an argument.
Check: on a scratch branch, the bump step run locally takes 0.0.1 → 0.0.2 in `Cargo.toml` and `Cargo.lock`, writes the changelog and makes one commit; `dist plan` at that commit announces `v0.0.2`; a second run gives 0.0.3; `just check` green.
Status: done 2026-09-04
Model: Opus 5
Check result: run in a clone of this repo so the working tree was untouched. With `v0.0.1`
tagged, `tools/release.sh patch --local` printed `current 0.0.1 -> release v0.0.2`, wrote
`version = "0.0.2"` into `Cargo.toml` and `Cargo.lock`, regenerated `CHANGELOG.md` and made
exactly one commit, `release: v0.0.2`. Tagging that and adding one ordinary commit, a second run
gave 0.0.3 with a `## 0.0.3` changelog section listing that commit, and `dist plan` at the
resulting commit announced `v0.0.3` — so dist reads the version the script wrote. `CHANGELOG.md`
contains zero `release: v` entries, which is the `cliff.toml` skip rule working. `just check`
green (12 test binaries).
The first run is the interesting one: because the script raises the version only when the current
one is *already tagged*, the very first release publishes 0.0.1 rather than skipping to 0.0.2.
`tools/release.sh patch --dry-run` on this repo confirms it — `current 0.0.1 -> release v0.0.1`.
Deviations: four files, not the two the task named. `cliff.toml` gained a `skip` rule so the
bookkeeping commit never appears in the next release's notes, and the shared logic went into
`tools/release.sh` rather than being written twice — the justfile recipe and the workflow are both
two-line callers of it. One thing found by running it: `[ -n "$X" ] && echo …` as a top-level list
ends a `set -e` script when the test is false; it is an `if` now.
Not verified: `git push` and `gh workflow run` are the two lines `--local` skips, so the dispatch
itself is unexercised until the first real release.
