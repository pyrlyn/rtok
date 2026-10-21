`mixed_chain` recorded only the first stage of each pipeline, so `git log | grep foo` was not a mix and `git log`'s 20-line formatter ran on grep's output. Every stage's program is counted now. `cargo build && cargo test` stays one family. A pipe whose last stage is already bounded (`| head`, `| tail -n`) still returns early from `is_bounded`.

Check: `mixed_program_forms` includes `git log | grep foo`; `same_program_forms` and `bounded_forms` stay green.
