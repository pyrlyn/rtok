---
status: todo
priority: P1
complexity: 5
readiness: 0%
agent:
---

# T8.3. Two-way sync engine and the GitHub remote

The board syncs both ways with one remote tracker. This task builds the engine in the
`kotlin-taskboard` package (`sync` module: three-way merge per field against a base in
`<board>/.sync/<remote>.json`, conflicts resolved by `prefer`, strays adopted, deletions
handled, `--dry-run`, writes through the engine as actor `sync:<remote>`), the GitHub remote
there (`github` module: Issues plus a Projects v2 Status column read and written), and in rtok
the commands `rtok task remote [set|unset]`, `sync`, `push`, `pull` and auto mode (push after
each write, pull before claim/next/ready, fail open). Spec: "Sync with a remote" in
[docs/design/task-board.md](../../docs/design/task-board.md).

Done means: against a fake GitHub (`FakeHttp`), a card moved on the board moves the issue
and its Project column, a card moved on the Project moves the file, both-side edits follow
`prefer`, a crashed push never duplicates an issue, and `export github` runs as a one-way
push alias.

Check: `./gradlew macosArm64Test cliTest e2eTest` green; the sync tests cover every rule in
"The sync run"; one manual `rtok task sync --dry-run` against a scratch GitHub repo.
