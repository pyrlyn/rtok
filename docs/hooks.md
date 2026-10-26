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

## Test
`./gradlew :rtok:macosArm64Test` (TypesTest, DeadlineTest, ResidentTest).

## Limitations / status
- Dispatch to plugins (`hooks/mod.rs`) and agent message push (`push.rs`) land after Phase 5.
- Windows named pipes are not ported (no Windows target).
