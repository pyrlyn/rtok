**T18.6 the release runs only on a green test suite** · T18.2, T18.5 · `.github/workflows/verify.yml`, `.github/workflows/release-plz.yml`, `.github/workflows/bump.yml`, `dist-workspace.toml`, `docs/release.md`, `tests/otel.rs`, `Cargo.toml`
Do: after v0.0.1 shipped, close the two holes the first real release exposed. (1) Nothing tested the code a release was cut from: `ci.yml` gates pull requests, and both release entry points dispatch `release.yml` without consulting it. Add a reusable `verify.yml` — the `ci.yml` matrix and recipes — and make the dispatch in `bump.yml` and in release-plz's `release` job depend on it. (2) `release-plz.yml` fell back to `GITHUB_TOKEN` when `RELEASE_PLZ_TOKEN` was absent, which opens a release pull request that starts no workflows: an empty checks list, not a red one. Fail the job with the reason instead. Modelled on `../ketch`, which runs the same gate inside its release and refuses to run without the token.
Check: the gate is not a dist `plan-jobs` entry — prove why with the generated workflow, not from memory; `actionlint` clean on all three workflows; `dist plan` still parses `dist-workspace.toml` and `release.yml` is byte-identical to the committed one; `just check` green.
Status: done 2026-09-09
Model: Opus 5
Check result: `plan-jobs = ["./verify"]` was tried first and reverted. `dist generate` wired it as
`custom-verify` with `build-local-artifacts` gaining `needs: custom-verify` — but `host` keeps
`if: always() && … && (needs.build-local-artifacts.result == 'skipped' || … == 'success')`, and a
failed custom job leaves that job `skipped`, so `host` and `announce` would still run. A red gate
would have published a Release with no binaries — worse than no gate, since `rtok-update` and the
installer read exactly those assets. The gate therefore sits in the two workflows that dispatch
`release.yml`, where a failure means no dispatch, no tag and no draft Release. `release.yml` was
restored with `git checkout` and `git diff` confirms it byte-identical; `dist plan` still lists all
three targets. `actionlint -shellcheck=` clean on `verify.yml`, `release-plz.yml` and `bump.yml`
(shellcheck itself could not run: the mise shim has no version set). `just check` green (146 tests).
Beta check the same day, on the published v0.0.1 rather than a local build: `rtok-installer.sh`
from `releases/latest` installed `rtok 0.0.1 (6c55b45a5)` and `rtok-update` into a scratch
`CARGO_HOME`; against a fresh `HOME` the binary ran `stats`, `config show --sources`, `doctor`,
`otel status` and `setup claude --dry-run` (7 additions + `mcpServers.rtok`) with exit 0, and
`hook PreToolUse` returned the wrapped command in 28 ms wall — garbage and empty stdin both
returned `{}` and exit 0, so fail-open holds in the shipped binary.
Deviation: six files, three more than the rule. `dist-workspace.toml` gains no setting, only the
comment recording why `plan-jobs` is the wrong place, so the next reader does not retry it; and
`docs/release.md` still described the token as optional and the tap as nonexistent. That document
is the only place the two remaining ketch-parity items are written down, both blocked on a secret
this session cannot create: `HOMEBREW_TAP_TOKEN` for the formula (the tap now exists and carries
ketch's cask), and `CODESIGN_CERTIFICATE` / `CODESIGN_CERTIFICATE_PASSWORD` for signing (ketch
holds the same Developer ID under different secret names; a secret's value cannot be read back out
of GitHub, so it has to be issued again). `tests/otel.rs` is the flake the gate would have tripped
over; a gate that fails at random is worse than the hole it closes, so it belongs to this task.
Same day, found while pushing this task: the T18.5 gate missed the ordinary squash merge. GitHub
appends the pull request number to the title, so merging #3 wrote `release: v0.0.1 (#3)` and
`grep -x` did not match it. Nothing was lost — v0.0.1 was already tagged, and `release.sh
--no-bump` refuses a released version — but the next merge would have dispatched no release at
all, silently. The gate now matches `^release: v[0-9]+\.[0-9]+\.[0-9]+( \(#[0-9]+\))?$`, checked
against all five shapes: squash with and without the number, the title line of a merge commit, an
ordinary task commit, and the same string inside a commit body (the last two correctly no match).
The T18.5 line "the failure mode is a second PR proposing the same version, not a wrong release"
turned out to be the actual behaviour, and permanent. release-plz 0.3.163 decides whether a version
shipped by comparing the packaged crate against the registry copy; `publish = false` means there is
no copy, so it reads rtok as never released and answers `next version is 0.0.1` regardless of
history. Reproduced in a clean clone with the `v0.0.1` tag fetched (`git describe` finds it) under
four configurations: as shipped, with `git_tag_enable = true`, with an explicit
`git_tag_name = "v{{ version }}"` (ketch's setting), and with a conventional `fix:` commit after
the tag — 0.0.1 every time. `../ketch` shares the configuration and its own config comment says it
is not a crate either, so it should meet this at its second release; untested there, it is on
`v0.1.0`. Nothing mis-releases: `release.sh --no-bump` refuses a tagged version and exits 0. Recorded
in `docs/release.md`, which now sends the second and later releases through **Bump and release**.
Whether release-plz still earns its place is the user's call, so T18.5 is left standing.
Making `just check` gate the release exposed a flaky test that would have blocked releases at
random: `stop_hook_spawns_the_flush_and_stays_under_10ms` waited 40 × 50 ms for the spawned child
to post, against the `flush_secs = 2` the same test writes — a 2 s deadline on a 2 s interval, so
the post lands on the boundary. It failed on ci run 34342891823 on a docs-only commit. The wait is
now 400 polls, matching `tests/proxy.rs`; a green run still breaks out on the first poll. Five
consecutive local runs of that test pass, and two of them took 4.72 s and 6.39 s — both past the
old 2 s deadline, so the budget was the cause and not the runner. ci is green on the fix.
Not proven here: that a red `verify` actually blocks the dispatch. It needs a release run with a
deliberately broken tree, and the only way to stage one is to publish from `main`.
Follow-up, same day, by request: every trace of the Homebrew release is commented out rather than
left half-described. `dist-workspace.toml` keeps `installers = ["shell"]` and carries the three
lines that would turn the formula on (`installers = ["shell", "homebrew"]`, `tap`, `publish-jobs`)
inside a delimited comment block saying it is off to be fixed later and blocked on
`HOMEBREW_TAP_TOKEN`; `docs/release.md` replaces the turn-on section with a plain statement that
`brew install rtok` is not a thing, followed by an HTML comment holding the three-step procedure;
`Cargo.toml` keeps `repository` and `homepage` — dist reads both when it builds a formula — with a
comment saying so. Checked: `dist plan` announces v0.0.1 with only the shell installer, updater and
sha256 artifacts and no homebrew entry, `just dist-generate` leaves `release.yml` byte-identical
(`git diff --stat` empty), and `just check` green. `README.md` line 23 and the `CHANGELOG.md` and
`done.md` history were left alone: they record the ketch install path and past events, and neither
offers a brew instruction.
