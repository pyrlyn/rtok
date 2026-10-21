Creator request 2026-09-24. When main CI fails, `revert-on-failure` (T84) must revert main and at once open a draft PR from a `revert-<branch>` branch that re-applies the work, titled with a `[Revert]` prefix. It reverted main and pushed the branch, but `gh pr create` failed with "GitHub Actions is not permitted to create or approve pull requests" (run 35997410439, T118.3's revert), leaving `revert-t118.3-gemini-extension` without a PR; the title was `Reapply <sha> — reverted after main CI failed`.

Plan: enable the repo setting "Allow GitHub Actions to create and approve pull requests" (creator approved; `default_workflow_permissions` stays `read`); title the PR `[Revert] <merged PR title>`, falling back to the commit subject for a direct push; delete the stale `revert-t118.3-gemini-extension` branch (T118.3 re-landed in #319).

Check: `actionlint` + `shellcheck` clean on `.github/workflows/ci.yml`; the title/branch logic run against the real API for 3138371 gives `revert-t118.3-gemini-extension` / `[Revert] T118.3: Gemini CLI extension tree: manifest, hooks.json, MCP, install`.

Result: setting on (`can_approve_pull_request_reviews: true`), stale branch deleted, `ci.yml` reads the merged PR once for both its head branch and title.
