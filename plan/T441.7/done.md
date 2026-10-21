Seventh subtask of T441 (task adapters): `adapter = "github"` keeps each task as one issue in the project's repository.

Check: the adapter against a mock GitHub server (create, sub-issue link, list, get, status, id collision, missing label, rate limit); `just check` green.

Result: `src/tasks/remote.rs` holds what the GitHub and GitLab adapters share: the `rtok` and `rtok:<id>` labels, the `R12. Title` issue title, the token lookup (`GH_TOKEN`/`GITHUB_TOKEN`, else `gh auth token`), and one blocking client that paces writes one second apart, waits out `retry-after` or the primary-limit reset up to a minute, and follows `Link: rel="next"` only on the API host, since every request carries the token. `src/tasks/github.rs` creates the issue with both labels, links a subtask as a sub-issue of its parent (a failed link only warns), maps `rtok:in-progress` and the close reason (`completed` → done, `not_planned` → closed), and refuses an issue GitHub stored without its label (no push access). An id another machine already issued comes back as the typed `Taken` error, and `Project::create` allocates the next id and tries again; the disk adapter returns the same error. reqwest gains its `blocking` feature. The Projects v2 Status field moved to T441.11 and `rtok task sync` to T441.12; `[tasks.github] project` is documented as read from T441.11 on. #814.

Status: done 2026-10-08
Model: Claude Code / claude-opus-5-5
