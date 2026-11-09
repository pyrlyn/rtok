# T8.8. Done

- Library PR: https://github.com/pyrlyn/packages/pull/5, tagged `0.0.2`.
- rtok PR: https://github.com/pyrlyn/rtok/pull/280

## What was done

- **`kotlin-taskboard`** is a new project in `pyrlyn/packages`, with the `core`, `files` and `testing` modules.
  - `core` holds the model, the `Store` SPI and the `Board` engine: revisions with `if_rev`, field merge, atomic claim-next, all-or-nothing batches, idempotency keys, leases with a liveness hook, and the journal with `changes` and `watch`.
  - `files` holds T8.1's board format, moved out of rtok as `FileStore`: flock on `.lock`, `.state/`, the redo log and hand-edit detection.
  - `testing` holds an in-memory store, a fake clock and a race kit.
- **rtok opens a `Board` for every layout and adapter.**
  - The board layout uses `FileStore`.
  - The legacy `files` and `plan` layouts and the GitHub and GitLab adapters are wrapped as `AdapterStore`, with engine state under `.rtok/taskboard/<kind>-<hash>/`.
- **Claims are leases.** `task_claims` only mirrors each agent's latest claim for the prompt hook.
- **rtok's own board code is deleted:** `BoardAdapter`, `BoardFile`, the ready-task selection, `setStatus` and `claimGuard`.
- **CI and release** pin `pyrlyn/packages` at `0.0.2`.

## How it was checked

- The package gate in `pyrlyn/packages` passed, including the race tests that open separate stores on one directory from several threads.
- `./gradlew macosArm64Test cliTest e2eTest` in rtok, with `RTOK_AGENT_ID` unset: passes, with 2331 unit tests, `cliTest`, and e2e 65 of 65.
