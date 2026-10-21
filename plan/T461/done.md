Cloud review finding (suspected): the Windows resident (`src/hooks/resident.rs`) exits only when `hook.lock` vanishes, while the Unix one also exits when its socket file vanishes. Not a bug. The Unix check exists because a new resident unlinks and rebinds the socket path, which would orphan the old listener. A named pipe has no file a user or another resident can delete, and `ServerOptions::first_pipe_instance(true)` makes a second resident fail to create the pipe while the first holds it, so there is no Windows counterpart to mirror. Added a comment on the Windows tick saying why; no behaviour change.

Check: read `listen` for both platforms; `just check`.

Result: comment only; `cargo check` and `just fmt-check` green.
Status: done 2026-10-10
Model: Claude Code / opus-5.5
