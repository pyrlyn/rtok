The skill from T411 starts with `rtok worktree whoami`, which reached `main` after v0.15.1, so an agent on the released binary hit "unrecognized subcommand 'whoami'" at its first step. Done: the skill names the fallback, `rtok agents whoami` for the id and `rtok worktree list` for the rest, until a release carries T411.

Check: `cargo nextest run --test skill`.
