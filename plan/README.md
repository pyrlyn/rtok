# rtok-kotlin

GitHub: https://github.com/pyrlyn/rtok

Kotlin/Native port of the Rust rtok CLI, a token-reduction tool for AI coding agents; CLI flags, output formats, config and on-disk formats stay interchangeable with the Rust binary.

## Notes

- Per-file port status: [PORTING.md](../PORTING.md). Decisions and history: [port-plan.md](../port-plan.md). Approved work not yet planned: [roadmap.md](../roadmap.md). Unapproved ideas: [ideas.md](../ideas.md).
- The task ids T1 to T6 came from a review of the repo (CI, `packages/kotlin-kit`, docs and layout, splitting `:rtok`, detekt baselines, binary size). Each task is a folder here once an agent claims it.
