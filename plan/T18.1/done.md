**T18.1 version 0.0.1 and a dispatchable release** · T10.4 · `Cargo.toml`, `dist-workspace.toml`, `.github/workflows/release.yml`
Do: `version = "0.0.1"`. In `dist-workspace.toml` turn on dispatch-style releases so the workflow is started from the Actions tab and dist creates the tag itself (this *replaces* the tag-push trigger; `tag: dry-run` is a plan-only run), and ship the self-updater alongside the binary so an installed `rtok` can update itself. Regenerate the workflow with `just dist-generate` — never hand-edit it.
Check: `just dist-plan` succeeds, announces `v0.0.1`, and still lists both darwin targets on `macos-14` / `macos-15-intel`; the regenerated `release.yml` carries a `workflow_dispatch` trigger whose `tag` input drives publishing; `rtok --version` prints `0.0.1`.
Status: done 2026-09-04
Model: Opus 5
Check result: `dist plan` exits 0, announces `v0.0.1` and keeps both darwin targets on their
current images — `macos-14` for `aarch64-apple-darwin`, `macos-15-intel` for `x86_64-apple-darwin`
(dist 0.32 already moved off the retired `macos-13`, so no runner needed changing). The
regenerated `release.yml` triggers on `workflow_dispatch` with a `tag` input, and its `plan` job
gates publishing on `inputs.tag != 'dry-run'`. Artifacts now include `rtok-<target>-update`
beside each archive, the installer and the Homebrew formula. `rtok --version` → `rtok 0.0.1
(1e2177b1e)`; `just check` green (12 test binaries).
Deviation from the task text: `dispatch-releases = true` *replaces* the tag-push trigger rather
than adding to it — dist creates the tag itself through `gh release create --target <sha>`. The
plan sentence claiming a tag push would keep working was corrected before committing.
Not verified: nothing has been released yet, so the archives have never been built on a runner.
Two prerequisites must exist before the first run — the `pyrlyn/homebrew-tap` repository and a
`HOMEBREW_TAP_TOKEN` secret — or `publish-homebrew-formula` fails after the Release is created.
