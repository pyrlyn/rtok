# Agent rules for rtok-kotlin

## Builds and tests: use the smart tasks
- Default: `mise run test-smart` (tests) and `mise run build-smart` (build). The script
  `scripts/smart.py` picks the run itself and prints the decision and the reason.
- It runs **FULL** (`./gradlew macosArm64Test cliTest e2eTest` / `assemble`) when any of these hold:
  - shared build config changed: `settings.gradle.kts`, root `build.gradle.kts`, `gradle.properties`,
    `gradle/libs.versions.toml`, `gradle/wrapper/`, `build-logic/`, `buildSrc/`, `mise.toml`, `scripts/smart.py`;
  - many modules changed: more than `SMART_MAX_MODULES` (default 3) or more than `SMART_MAX_SHARE`
    percent of all modules (default 40);
  - the diff base cannot be determined.
- Otherwise it runs **AFFECTED**: the changed modules plus every module that depends on them.
- Diff base: `--base <ref>`, else `$SMART_BASE`, else `git merge-base HEAD origin/main`; uncommitted
  and untracked files count too. `mise run test-affected` / `build-affected` force the affected run.
- **Always run a full run before a release or a merge**: `./gradlew macosArm64Test cliTest e2eTest`
  (and `./gradlew :rtok:linkReleaseExecutableMacosArm64` for a release).

## Testing
- Packages/libraries contain only tests of the package itself. Tests that verify the app
  (rtok-kotlin) through a package live in the app next to its other tests. Applies to the whole
  Kotlin port, including snapshot tests.
- Snapshot tests use `pyrlyn.snapshot:snapshot` (`../../packages/kotlin-snapshot`); see
  docs/testing.md "Snapshot tests".

## Git
- Commit locally in small green steps. Do not push `main` and never force-push; every change ends in a PR.
