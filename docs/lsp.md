# LSP backend for `graph`

By default the `graph` plugin answers `symbol`, `callers`, `impact`, `outline` and
`explore` from its tree-sitter-tags index in SQLite (`backend = "tags"`). Setting
`backend = "lsp"` routes those same five MCP tools — same names, same callers —
through a language server spoken to over stdio instead. The tags index is then
not consulted for that call. Each LSP answer records one `Measurement` row with
`plugin = "graph"` and `kind = "lsp.symbol" | "lsp.callers" | "lsp.impact" |
"lsp.outline" | "lsp.explore"`, so `rtok stats` attributes it like any other saving.

Each of the five tools takes an optional `project` (id or directory). Without it a call answers for
the working directory's project and the projects it links to; the language server answers one root,
so with `backend = "lsp"` only the first project of that scope answers and the reply says so. (`auto` asks every project, see below.)

The server is picked from the workspace root — rtok never links or shells out to
anything else (D6); it spawns one of these from `PATH`:

| Root marker | Server command |
|-------------|----------------|
| `Cargo.toml` | `rust-analyzer` |
| `compile_commands.json` | `clangd` |
| `tsconfig.json` | `typescript-language-server --stdio` |
| `pubspec.yaml` | `dart language-server` |

The walkthrough below covers Rust and Dart.

## Rust: rust-analyzer

Install once via rustup, then check the binary answers:

```bash
rustup component add rust-analyzer
rust-analyzer --version
```

`rustup` puts `rust-analyzer` on `PATH`; rtok additionally resolves it through
`rustup which rust-analyzer` when rustup is present.

## Dart: Dart SDK

Install the SDK (https://dart.dev/get-dart), then check it answers:

```bash
dart --version
```

The server is the SDK's own `language-server` subcommand — rtok spawns
`dart language-server`, so no extra install step exists beyond the SDK itself.

## Point a project at it

The marker file decides the workspace root: `Cargo.toml` for a Rust crate,
`pubspec.yaml` for a Dart package. Either set it per project in
`<git root>/.rtok.toml`:

```toml
[plugins.graph]
backend = "lsp"
```

or per invocation from the environment:

```bash
RTOK_PLUGINS_GRAPH_BACKEND=lsp rtok config get plugins.graph.backend
```

which prints `lsp` (default is `tags`). With `--sources` the row reads
`plugins.graph.backend = lsp (env)`:

```bash
rtok config show --sources | grep plugins.graph.backend
```

## Confirm it works

Call MCP `outline` on a sample file, then `callers` on one of its symbols.
`tests/graph_lsp_gate.rs` is the runnable version of this check: it gets
`outline` of a two-symbol `lib/main.dart` through `dart language-server`, asserts
the `OnlyTyped` reference hits through rust-analyzer (it hits through tags too
since T52.5; the tags-miss pin is now a macro-body `macro_callee` reference),
and asserts one `lsp*` measurement row:

```bash
cargo test --test graph_lsp_gate
```

All six tests pass when both servers are on `PATH`; the Dart and LSP tests
skip otherwise.

## Backend `auto`

`backend = "auto"` (T329.9) is opt-in; the default stays `tags`. It chooses per project and language
instead of once for the process. A project whose language has a server (the table above) is asked
first and the answer is headed `(lsp)`. A language with no server gives the tags answer headed
`(tags)`; a server that is missing, not ready, slower than `lsp_timeout_ms` (default 40000) or dead
mid-session gives the tags answer headed `(tags; lsp: <reason>)`, so a crash is a notice, not an
error. Every tags answer records a `tags.symbol | tags.callers | tags.impact | tags.outline |
tags.explore` measurement row beside the `lsp.*` ones, so `rtok stats` shows how often each mode
answered.

In a scope of several projects where a server is installed for some, each project answers for
itself and its part is labelled `[name]` and headed with its mode, so a Rust project can come from
rust-analyzer and a Go project from tags in one reply. A linked project's callers are that project's
own answer; the linked tags traversal runs only when no project of the scope has a server installed,
and then each project's mode is one `[name] (tags)` line at the top.

`[plugins.graph.backend_by_language]` pins one language, named by the project's marker file (`rust`,
`c`, `typescript`, `dart`, `go`, `python`, `javascript`): `go = "tags"` keeps Go on the index while the
rest use `auto`. `text` is not accepted until T329.10.

## Checked once

Under `lsp` and `auto` (T329.11) rtok checks each project once: the marker file, the server binary on
`PATH`, and the first request that starts the server. The result is the project's record, and later
requests use it without looking again. A missing server is not searched for on every call, and a server
that broke (it died, closed its pipe or timed out) is not restarted on every call; it is dropped from
that project's answers once, with the reason, and the project answers from tags. A server's error reply
to one request is not a break. Concurrent first requests share one check.

The record is kept in memory and mirrored into the store, so `rtok graph projects --json` (and the web
page) show it under `backend`: `backend` (`lsp` or `tags`), `language`, `server` (the language has one),
`reason`, `config` (the `backend` value it was made under), `checked_at` and `next_probe_at` (unix
seconds; the earliest a health check retries a failed record). Changing `backend` or
`backend_by_language` re-checks only the projects whose value changed. A new process uses a record
another process wrote until `next_probe_at`, then checks for itself, so restarting rtok after installing
the server picks it up. Nothing re-checks a running process yet; that is the background health check
(T329.17).

## Without the server

`backend = "lsp"` does not fail when the server cannot answer. When the binary
is missing, no marker file is found above the queried file, the server is not
ready or has died, or it answers "nothing" for a name the tags index knows, the
tool gives the tags answer headed `(tags; lsp: <reason>)` — for example
`(tags; lsp: rust-analyzer not on PATH)`, or
`(tags; lsp: no Cargo.toml / compile_commands.json / tsconfig.json / pubspec.yaml in <root>)` —
so you can see which backend spoke and why. Each fallback records an
`lsp_fallback` measurement row (its `before_bytes` is the time lost). To go back
to the built-in index, set `backend = "tags"` again (the default); the tags
answers are byte-identical with the flag off, per the `graph_lsp_gate` contract
test. The default stays `tags`: a default change needs a new gate with recorded
`lsp.*` latency rows.
