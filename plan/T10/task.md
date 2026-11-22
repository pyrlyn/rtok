---
status: in progress
priority: P2
complexity: 4
readiness: 60%
agent: Claude Code / claude-fable-5-2
---

# T10. Architecture improvements from the whole-repo review

The T7 review looked at the architecture of every unit besides its bugs and left a list of structural
problems: helpers copied between modules that have already drifted, a filesystem primitive that lower
layers import from the agent SDK, work repeated on a hot path, and places where one failure takes a
whole page or process down. The creator asked to apply the improvements that follow from that review.

Done means the changes below are in, each one small and behaviour-preserving unless `done.md` says
otherwise, the suite is green, and `done.md` lists what was changed and which findings were left and why.

## Execution plan

1. One implementation per helper: code-point counting and splitting (`rtok.sys.scalarCount`,
   `scalarList`), `rustLines`, the environment reader.
2. Layering: move `writeAtomic` from the agent SDK down to rtok-sys, with a test of its own there.
3. Hot path: `Runtime.configJson` stops re-serialising the whole config on every lookup.
4. Under load: the HTTP server holds its handler lock only while the handler runs, not while a client
   uploads its body. A test that fails on the old code comes first.
5. Small costs: byte buffers in the hook client that boxed every byte.
6. Findings that change behaviour or need a product decision go to `done.md`, not into code.

Stacked on T9 (`t9-strip-dead`).

Check: `./gradlew macosArm64Test cliTest e2eTest` passes and `mise run size-check` stays under the
budget on the PR head.
