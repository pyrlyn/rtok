# Hook client protocol (`rtok.hook`, port of `rtok-hook`)

**How it works.** A host hook (e.g. Claude Code `PreToolUse`) runs the thin client, which connects to
the resident `rtok` at `<RTOK_HOME>/hook.sock` and sends one frame: `u32 LE length` + fields
`version, fingerprint(u64 LE), event, host, cwd, stdin[, pid(u32 LE)]`, each `u32 LE length`-prefixed.
The reply is `0 + stdout` or `1` (pass through). The fingerprint is FNV-1a over sorted `RTOK_*`,
`HOME`, `USERPROFILE`, `OTEL_EXPORTER_OTLP_*`, `GROK_HOOK_EVENT`, `DEVIN_PROJECT_DIR`, so a resident
started under other config is not reused. Frames are byte-identical to Rust rtok.
`expandWith` resolves `~/.rtok/…` to `RTOK_HOME` and `~/…` to the user home.
**Enable / disable.** Deadline: `RTOK_HOOK_DEADLINE_MS` (default 3000). Resident server side lands with `src/hooks`.
**Test.** `./gradlew :rtok-hook:macosArm64Test`.
**Limitations.** Unix sockets only (no Windows named pipe yet); `sockaddr_un` is laid out by hand for
Darwin/Linux because the posix klib does not expose it. No socket round-trip test yet.
