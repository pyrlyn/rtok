# CI platforms

Principle: everything that can be built, checked or tested on Linux runs on Linux
(`ubuntu-latest`). Windows and macOS runners keep only what exists or matters only on that
platform. The Windows and macOS test jobs themselves are kept; they are optional
(`continue-on-error`), so they never fail the `ci` workflow.

The shared checks come from [pyrlyn/ci](https://github.com/pyrlyn/ci) through
`.github/workflows/pipeline.yml` and `.github/infra.yml`; their runner defaults are set there
(pyrlyn/ci#103: OS-independent jobs default to `ubuntu-latest`, `CI_RUNNER` still overrides).

## Gate

`pipeline / gate` (the protect-main required check) is pyrlyn/ci's gate job. It covers
pyrlyn/ci's own jobs (config, CodeQL, Semgrep, actionlint, license, commits, no-pinned-ci, ...),
not this repository's `ci.yml` jobs; the bump and Dependabot flows wait for the whole
`pipeline.yml` run instead. With pyrlyn/ci#103 the gate fails only when a needed job's result
is `failure`: `skipped` and `cancelled` pass. A run cancelled before anything failed (a newer
push, a manual or scripted cancel) therefore passes the gate.

## Per job

| Job | Platform | Removed from Windows/macOS | Kept there, and why |
|-----|----------|----------------------------|---------------------|
| `ci / lint` (fmt, clippy, jscpd, oxlint, pytest, publish-dry, plugin versions) | Linux | Windows clippy (was in `windows-build`); macOS clippy (was in `macos-build`) | nothing |
| `ci / check` (build, Playwright e2e, examples) | Linux | the macOS matrix entry | nothing |
| `ci / build-tests` + `ci / test` (full nextest suite, 2 shards) | Linux | the macOS matrix entries | nothing: every test that compiles on Linux runs here |
| `ci / windows` | Windows, optional | the full-suite shards and the archive job | the `cfg(windows)` tests, picked by its nextest `-E` filter |
| `ci / macos` | macOS, optional | the full suite and the archive upload | the `cfg(target_os = "macos")` tests, picked by its nextest `-E` filter |
| `ci / spa`, `ci / docs`, `ci / hakari` | Linux | none (never ran elsewhere) | nothing |
| `ci / macos-runner` | Linux | none | only resolves which Mac label `ci / macos` uses |
| `pipeline / config`, `commits`, `gate`, `main-failure`, `lint / actionlint`, `no-pinned-ci`, `codeql / *` | Linux (pyrlyn/ci#103 defaults) | the self-hosted Mac default (`xcode-27-local`) | nothing |
| `pipeline / dotnet` | off | the job itself (`dotnet: {enabled: false}`): no .NET project here | nothing |
| `pipeline / license / license-check` | Linux after pyrlyn/ci#102 | handled in pyrlyn/ci#102, not here | nothing |
| `changes` (pipeline.yml), graph-review, marketplace, competitor-bench, bump | Linux | none (already Linux) | nothing |
| `release.yml` build matrix (cargo-dist) | each release target | nothing | a native binary per target: platform-only by nature |
| macOS signing / notarisation (pyrlyn/ci) | macOS | nothing | Apple tooling exists only on macOS |

Also removed: the `prep_target_dir.py` steps in `check` and `build-tests`. They ran only on
self-hosted runners, and those Linux jobs run on hosted `ubuntu-latest`, so the steps never ran.
The `macos` job keeps its own copy: it does run on the self-hosted Mac.

## Keeping the platform filters current

`ci / windows` and `ci / macos` list their tests by name in `PLATFORM_TESTS`
(`.github/workflows/ci.yml`). A new or renamed `#[cfg(windows)]` or
`#[cfg(target_os = "macos")]` test must be added there, or it runs nowhere. An empty selection
passes (`--no-tests=pass`).

## Known gap: `cfg(windows)` clippy

Clippy runs only on Linux, so code behind `cfg(windows)` (and `cfg(target_os = "macos")`) is no
longer linted in CI. The Windows clippy step used to catch that lint debt (T902). Options, none
applied yet: run clippy for `--target x86_64-pc-windows-msvc` on Linux (needs the target's std;
crates with a native build script may not cross-check), or run `cargo clippy` locally on
Windows before touching Windows-only code.
