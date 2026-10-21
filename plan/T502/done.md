Status: done 2026-10-10
Model: Claude Code / claude-opus-5-5

Creator decision 2026-10-10. Since 2026-10-08 the `protect-main` ruleset requires a pull request and a green `gate` on main, and its only bypass is the admin role through a PR. `ci / revert-on-failure` (T84, T253) still pushed `ci: auto-revert <sha>` straight to main, so the push was rejected (`push declined due to repository rule violations`, main pipeline runs 38043322970 and 38044250038, 2026-10-10): the job failed, main stayed red and nothing was reverted. With the ruleset, a red main push comes only from merge skew or a flake (the docs-only `efe19f07` failed `spa`), which should be fixed forward, not reverted. Rejected options: a revert PR with auto-merge (a `GITHUB_TOKEN` PR starts no workflow, so `gate` never runs without a PAT or App secret) and a ruleset bypass for GitHub Actions (any workflow with `contents: write` could push past the checks). `pyrlyn/ci` and the ruleset stay as they are.

Plan: delete the `revert-on-failure` job from `.github/workflows/ci.yml` and the comments that describe it; lower the `ci` caller permissions in `.github/workflows/pipeline.yml` to `contents: read` and `actions: write`, since no ci.yml job writes contents or pull requests now.

Check: no `revert-on-failure` left in `.github/`; the PR's pipeline passes (actionlint runs in `pipeline`); `just check`.

Result: job and its comments removed; the `ci` caller runs with `contents: read` and `actions: write`.
