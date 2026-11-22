# T10. Done

Five structural changes from the whole-repo review are in. Each is small; the two that change behaviour
are named below. The rest of the review's architecture findings are listed at the end with the reason
each one was left. Stacked on T9 (#290).

## Changed

- **One implementation per helper.** Nine private code-point helpers (rtok-store `Agents`, `Cache`,
  `Embed`, `GraphEvents`; rtok `Tokens`, `model/Agents`, `memory/Pack`, `graph/Draw`, `report/PdfText`)
  are gone; their callers use `rtok.sys.scalarCount`, `scalarList` and `takeScalars`. Three copies of
  `rustLines` became `rtok.sys.rustLines`, and three CLI files read the environment through
  `rtok.config.envVar` instead of a private wrapper each. Before writing anything the existing shared
  helpers in rtok-sys and rtok-config were searched; nothing new was added for this item.
- **`writeAtomic` lives in rtok-sys** (`Atomic.kt`). Task files, daemon state, the transcript cache and
  the shell rc files imported the agent SDK only to reach it. The SDK's `write` calls the rtok-sys one;
  the function has tests of its own now (mode kept, symlink written through, no temp file left).
- **`Runtime.configJson` encodes the config once per `Config` instance.** It used to serialise the whole
  config on every plugin lookup. `Config` is immutable, so the cache is keyed by instance and a
  reassigned `config` is encoded again.
- **The HTTP server takes its handler lock after the body is read.** `serveBlocking` held the lock while
  it read the request body, so one client that stalled mid-upload held up every other request, `/health`
  included. `aStalledUploadDoesNotHoldUpOtherRequests` fails on the old code with a timeout and passes
  now. The handler itself still never overlaps.
- **The hook client builds its frames from byte arrays.** `Request.encode` and `fingerprint` collected
  bytes in an `ArrayList<Byte>`, one boxed byte per element, on every hook call.

## Behaviour changes

- **Lone surrogates.** Four of the removed helpers (`Embed`, `GraphEvents`, `PdfText`, `memory/Pack`) paired a high
  surrogate with whatever followed it, and one (`model/Agents`) counted every non-low-surrogate unit. The
  shared helpers pair only a valid high+low couple, as Rust `chars()` does on valid text. Results differ
  only for strings with an unpaired surrogate.
- **Request bodies are read concurrently** on the `rtok web` server (see above). Each is still capped by
  `maxBody`.

## Findings left, and why

Each needs a product decision, changes a format or behaviour, or is larger than a review follow-up.

- **A failing page blanks the whole snapshot** (`Snapshot.build`). Wrapping each page is easy, but the
  web client treats any `error` key as a failed frame and recognises a snapshot by `plugins` being an
  array, so a per-page failure has nowhere visible to go. It needs a wire field (for example
  `page_errors`) and support in the three clients.
- **The HTTP client has no response size bound.** Every upstream is one the user configured, or docs.rs;
  the request timeout bounds the time. A cap needs a number per caller (a rustdoc dump and a batch result
  are legitimately large).
- **SIGPIPE is ignored per long-running command, not once in `main`.** Doing it in `main` changes what
  `rtok … | head` does for every one-shot command.
- **Config loading**: four entry points with different layering, the hot-path loaders, the global
  mutable hooks in rtok-config, `StatPreview` as a process-wide flag.
- **Process and lock plumbing in rtok-sys**: the pipe/spawn/wait protocol written three times, no single
  write-all-retry-EINTR primitive, CLOEXEC handled differently per lock, lock probing by taking the lock,
  the worktree lock rewrite that is not compare-and-swap.
- **Tasks and worktrees**: `rtok.tasks` reaching into `rtok.worktree` for the caller's identity, owner
  validation split between `Add.plan` and `Claim.run`, two id counters reconciled by hand, `TaskAdapter`
  write methods only `AdapterStore` uses, the half-wired finish/cleanup lifecycle (see T9's report).
- **Agents**: the hook-ownership predicate copied in five hosts, `installed()` detected by substring,
  ownership defaults that fail open (`onlyOurs = false`), HOME/XDG resolution done twice, two JSON
  editing paths.
- **Plugins**: four quote-aware shell scanners and five whitespace tokenisers beside the real lexer,
  three JSON canonicalisers, the pointer/raw/archive decision implemented twice, WASM plugins
  instantiated on every registry build although no surface dispatches to them, note text sanitised at one
  reader instead of at the save door.
- **Graph and indexing**: full and incremental indexing duplicate the parsed-to-store step, no resource
  limits in the indexing pipeline, walk semantics that differ from Rust `ignore`, ad hoc worker
  lifecycles, the stdio MCP server sharing one `Runtime` with its watch worker.
- **Daemon and doctor**: the daemon's doctor page uses the daemon's cwd as the project and starts the MCP
  servers of that directory's `.mcp.json`; pages open the store and `Runtime` more than once per build.
- **Telemetry**: two OTLP exporters with separate failure handling.

## Checks

- `./gradlew macosArm64Test cliTest e2eTest --continue`: green (ktlint and detekt run inside it). It ran
  on the six code commits; the merge of T9 that followed brought one test-only line (the `mode_t` width
  in `WalkTest`), which passes on its own.
- `mise run size-check`: 74,359,576 B of the 79,000,000 B budget (16 B under the T9 head).
- `aStalledUploadDoesNotHoldUpOtherRequests` was run against the old server first: it failed with a
  timeout, and passes with the change.

Linux cannot be built on this host; CI on the PR is its Linux build.
