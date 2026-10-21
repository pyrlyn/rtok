**T18.5 release-plz: the version as a pull request** · T18.2 · `release-plz.toml`, `.github/workflows/release-plz.yml`, `tools/release.sh`
Do: a second entry point beside the Bump workflow — release-plz keeps one `release: vX.Y.Z` pull request open on `main` with the next version and the `cliff.toml` changelog; merging it dispatches the dist Release workflow through `tools/release.sh --no-bump`. release-plz neither tags nor publishes (`publish = false`, `git_tag_enable = false`, `git_release_enable = false`): dist does both, and the tag dist pushes is what release-plz reads to know a version is out. No new secret: `GITHUB_TOKEN`, with `RELEASE_PLZ_TOKEN` as an optional upgrade for CI on the PR.
Check: `release-plz update --dry-run` on this checkout (no tags yet) keeps `0.0.1` and writes a `## 0.0.1` changelog section grouped like `just changelog`; `actionlint` accepts the workflow; in a scratch clone with a `v0.0.1` tag, `tools/release.sh patch --no-bump` prints "already released" and exits 0 before any push, and without the tag reaches the push and dispatch; `bash -n` clean; `just check` green.
Status: done 2026-09-07
Model: Fable 5.1
Check result: `release-plz update` (release-plz 0.3.162 via `mise x ubi:`; it has no `--dry-run`
and refuses a dirty tree, so it ran in a scratch clone with the change committed) left
`Cargo.toml` at `0.0.1` — "determining next version for rtok 0.0.1", no bump for a version that
was never tagged — and wrote a `## 0.0.1 — 2026-09-07` section to `CHANGELOG.md` under the same
`### Tasks` / `### Plan` groups as `just changelog`, so `changelog_config = "cliff.toml"` is
honoured. `actionlint` found one real error on the first pass — an unquoted `if:` holding
`'release: v'` is a YAML mapping — fixed by quoting; second pass clean, `bump.yml` clean.
Scratch clone with a `v0.0.1` tag: `release.sh patch --no-bump` printed "current 0.0.1 -> release
v0.0.2" then "v0.0.1 is already released; the next version comes from a release PR", exit 0, no
push; without the tag it reached `git push` / `gh workflow run` (both failed, as a local clone
without a token must) and committed nothing. `bash -n` clean; `just check` green (146 tests).
Not proven here: that release-plz on GitHub reads the `v*` tag dist pushes when `publish = false`
and `git_tag_enable = false` — the docs say tags are the record for unpublished packages; the first
merged release PR is the test, and the failure mode is a second PR proposing the same version,
not a wrong release. Deviation: four files plus the bookkeeping, one more than the rule —
`docs/release.md` gained the paragraph that explains the second entry point.
Follow-up, same day: the first push of this task fired the `release` job, not `release-pr` — the
commit *body* contained the literal `release: vX.Y.Z`, and `contains(head_commit.message, …)` is
not line-anchored — and `release.sh --no-bump` dispatched dist Release run 34150922080 for
v0.0.1. Cancelled during build-local-artifacts; `git ls-remote --tags` and `gh release list`
stayed empty, so nothing was published. Fix: a `gate` job runs
`git log -1 --format=%B | grep -qxE 'release: v[0-9]+\.[0-9]+\.[0-9]+'` and both jobs branch on
its output; checked locally against a squash message, a merge-commit message (both match) and
the T18.5 commit (no match); `actionlint` clean.
