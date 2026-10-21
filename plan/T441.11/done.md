Split from T441.7: with `[tasks.github] project` set, keep each task's issue on that Projects v2 board with its Status in step with the task status; `project = 0` stays issues only.

Check: mock-server tests in `src/tasks/github_project.rs` (a new issue joins as Todo, every status maps to its option with the ids resolved once, `project = 0` makes no GraphQL call, five failure modes still write the task, a failed resolve is retried); `just check` green.

Result: new `src/tasks/github_project.rs`: one GraphQL query resolves the user- or org-owned project (owner from the repo), its `Status` single-select field and option ids, cached per adapter and not cached on failure, so a long MCP session recovers from one network blip. After every create and status write `addProjectV2ItemById` (idempotent, so an older issue joins on its next change) and `updateProjectV2ItemFieldValue` set the option: open → Todo/To do, in-progress → In Progress, done and closed → Done (the default field has no won't-do column; the `not_planned` reason stays on the issue). Names match case-insensitively. Any project problem (no project, missing token scope, no Status field or option, HTTP error, no `node_id`) only warns and never fails the task write. `GithubAdapter::with_project` wires it; the config docs (en/ru/uk, `default.toml`, `config/mod.rs`) describe the real behaviour. GitHub Enterprise's `/api/graphql` path is not handled, since there is no base-URL setting yet. #817.

Status: done 2026-10-08
Model: Claude Code / claude-sonnet-5-5
