---
status: todo
priority: P1
complexity: 3
readiness: 0%
agent:
---

# T8.9. JSON-RPC API for the task board

Programs in other languages get the library's API without Kotlin. The package's `rpc`
module maps it one to one onto JSON-RPC 2.0 (methods, error codes, server notifications for
`board.subscribe`), ships a transport-free dispatcher, a Kotlin client and an OpenRPC
document that a test keeps equal to the code. rtok serves it over `rtok task rpc --stdio`
and on a Unix socket of the daemon (directory 0700, socket 0600). Spec: "JSON-RPC" in
[docs/design/task-board.md](../../docs/design/task-board.md).

Done means: a client that only reads the OpenRPC document can list, create, claim, batch and
watch tasks over both transports; the socket refuses other users; the CLI and MCP keep
working while the daemon is down.

Check: the package gate green; `./gradlew macosArm64Test cliTest e2eTest` green with an e2e
case that drives `rtok task rpc --stdio` and the daemon socket.
