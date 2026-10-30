---
status: in progress
priority: P0
complexity: 3
readiness: 90%
agent: Claude Code / claude-sonnet-5-5
---

# T1. CI for macOS and Linux

The repo has no `.github/` directory, so nothing runs the test suite on a pull request. Add GitHub Actions that run on every PR and on push to `main`, on macOS arm64 and Linux x86_64. Done means: the workflow is merged-ready in a PR, the macOS job is green on that PR, the Linux job result is reported (which tests fail, with the Linux bugs from `port-plan.md` "P1 — Linux"), and the creator has decided whether the Linux job is required.

Out of scope: fixing the Linux bugs, detekt (T5) and binary-size (T6) gates.

## Execution plan

1. Follow the sibling project `pyrlyn/octo`: the workflow is generated from `.github/workflows/ci.main.kts` (github-workflows-kt) into `ci.yml`, and a `check_yaml_consistency` job fails when the YAML is hand-edited. `mise.toml` already installs the Kotlin CLI for such scripts.
2. Jobs:
   - `macos`: `macos-latest` (Apple Silicon only), `./gradlew macosArm64Test cliTest e2eTest`.
   - `linux`: `ubuntu-latest`, `./gradlew -Prtok.allTargets=true linuxX64Test cliTest e2eTest` (on a Linux x64 host `cliTest` and `e2eTest` pick `linuxX64`).
3. Both jobs install the tools from `mise.toml` with `jdx/mise-action`, check out `pyrlyn/packages` at a pinned tag and point `SHARED_SETTINGS_DIR`, `KOTLIN_WASM_DIR`, `KOTLIN_SNAPSHOT_DIR`, `KOTLIN_WEBGL_DIR` and `KOTLIN_KIT_DIR` at it (`KOTLIN_KIT_DIR` is for T2). Gradle is cached by `gradle/actions/setup-gradle`; `~/.konan` by `actions/cache`.
4. Every action is pinned to an exact released version.
5. Check: run `ci.main.kts` to regenerate `ci.yml`, open the PR, read both jobs' results, record them in `done.md`.
