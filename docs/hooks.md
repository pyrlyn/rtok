# Hooks (`rtok hook`)

## How it works
Hosts (Claude Code, Cursor, Copilot CLI, Devin, Gemini CLI, CodeWhale, Cline, Command Code, Grok Build)
run `rtok hook <Event> [--host H]` with JSON on stdin. `hooks/Types.kt` parses it into `HookInput`
(unknown keys round-trip in `extra`), each host adapter maps it onto Claude's shape, and the event views
(`preTool`, `postTool`, `sessionStart`, `promptSubmit`, `subagentStart`, `preCompact`) feed the plugins.
The answer is `HookOutput` JSON; `{}` means "no opinion" (fail open).

- **Deadline** (`Deadline.kt`): a watchdog prints `{}` and exits 0 when the call outlives
  `RTOK_HOOK_DEADLINE_MS` (default 3000); a `Gate` makes sure only one answer is written.
- **Resident** (`Resident.kt`, `rtok hook --serve`): optional server on `<home>/hook.sock` (mode 0600,
  bound through a private 0700 directory), one per home (`hook.lock` flock). It answers only clients
  with the same environment fingerprint and version; a different version makes it exit. Wire format:
  see `docs/hook-protocol.md` — identical to Rust, so Rust and Kotlin clients/residents interoperate.

## Enable / disable
Installed by `rtok agents install` (Phase 9). The resident is optional; stop it by deleting `hook.lock`
or `hook.sock`.

## Dispatcher (`hooks/Dispatch.kt`, `HostOutputs.kt`, `Push.kt`)
`rtok hook <event> [--host <h>]` reads at most `core.hook_max_input_bytes` of stdin, adapts the host's
payload to Claude's shape, opens the store with a 5 ms lock wait, registers the agent, runs every enabled
plugin for the event (a throwing plugin is logged at `error` and skipped), pushes undelivered agent
messages (T288), caps PostToolUse context at `[plugins.inject] budget_tokens` (rest archived behind a
`dropped:post_tool:` marker), and writes the host's stdout shape (copilot, cursor, gemini, codewhale, cline,
codebuddy, codex; Claude as is). Host-specific objects are written with sorted keys, like serde_json.
Slow events warn once per `[hook] slow_warn_s`. A SessionEnd that hits a locked store is re-run detached.
Under Claude, a PreToolUse on one of rtok's own MCP tools (matcher `mcp__rtok__.*`) adds `rtok_caller` to the
tool's input, over anything already there, so a server shared by sessions knows its caller (`docs/agents.md`).
`--serve` runs the resident and answers each request through the same dispatcher.

## Test
`./gradlew :rtok:macosArm64Test` (TypesTest, DeadlineTest, ResidentTest, DispatchTest 26).

## Limitations / status
- Steps owned by later phases are reached through `HookLinks` and do nothing until those land:
  worktree pending claim / unlock (Phase 10 worktree), otel export spawn (Phase 10 otel), Cursor MCP result
  shortening (Phase 8 `mcp::wrap`). `rtok hook` for worktree host events (`worktree::host`) is not routed yet.
- Bounded stdin read is exact; the panic path maps any Kotlin exception to fail-open `{}`.
- Windows named pipes are not ported (no Windows target).
