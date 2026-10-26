# Plugin runtime (host)

Port of `src/plugin.rs`. `rtok.plugin.Runtime` is the host side of the plugin contract: it owns the merged
`Config` and the one `Store`, and implements every capability interface of `rtok-plugin-sdk`
(`Host`, `Archive`, `Notes`, `ReadCache`, `Ledger`, `Symbols`) by delegating to the store. A plugin only
ever sees those interfaces through `Ctx`, so the sdk stays free of SQLite.

## How it works
- `Runtime.open(config, session)` opens `core.db_path`; `Runtime.inMemory(session)` gives tests a default
  config rebased under a fresh temp dir plus an in-memory store; `close()` removes that dir (T644).
- `record` writes a `measurements` row (deduplicated by `once`, T245). `deferMeasurements` /
  `flushMeasurements` batch the rows into one transaction for the hook budget.
- `recordCall` / `recordPluginRun` upsert the session (project resolved from `cwd`, so a linked worktree
  is attributed to its main repository, T154) and nest under `callId`.
- `log` goes through `rtok.recordLog`: the file line, then the `logs` row when `[log] to_db`; each
  swallows its own errors (fail open).
- `identicalResult` turns a same-session, same-context repeat into a pointer (T65.1, T127);
  `fitBudget` trims text to a token budget.
- `restrictArchives` limits expansion to ids this runtime archived (remote surfaces).
- Shared mutable state (deferred queue, graph-watch pending set, archive grants) uses
  `kotlin.concurrent.AtomicReference` with immutable values; no extra library.

## Enable / disable
Always on; plugins are enabled per `[plugins.<id>] enabled`.

## Test
`./gradlew :rtok:macosArm64Test` — `RuntimeTest` (8).

## Limitations
- `[plugins.memory.embed] provider = "http"` needs an HTTP client, which is not approved yet; the runtime
  keeps the offline hash embedder for it.
