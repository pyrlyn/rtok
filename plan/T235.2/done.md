Load-incident context in `done.md` → T235.1.

- Every agent Bash call runs as `rtok run -- <cmd>`, which spawns `/bin/zsh -lc` — a login shell — although the harness has already sourced its own shell snapshot (`zsh -c source <snapshot> && rtok run -- ...`), so each call starts two shells. Idle cost measured: `rtok run -- true` 0.16 s, `zsh -lc true` 0.15 s, `zsh -c true` 0.00 s — nearly all of the wrapper's cost is the login shell. Under that load even `rtok run -- echo hi` did not return within 30 s (a fresh terminal shell did not reach its prompt either, so load was the root cause, but the login shell multiplies it per call).

Check: `rtok run` starts no login shell unless something it needs comes only from the login profile (decide and record why; measure the per-call saving with hyperfine on idle and on a loaded host).

Do (Claude Code / claude-opus-5-5, 2026-09-24): `shell_args` passes `-c` instead of `-lc` to a POSIX shell. Nothing `rtok run` needs comes only from the login profile. The host already ran its profile in the shell that runs `rtok run -- <cmd>` (Claude Code sources its shell snapshot; Codex and Cursor start their own shells), and `rtok run` passes that environment on unchanged. That is the environment the unwrapped command would have had. A second login shell added nothing but cost, and on macOS `/etc/zprofile`'s `path_helper` could even reorder `PATH` under the command. `cmd` and PowerShell are unchanged: `/D /C` and `-NoProfile` already skip their profiles. `plugins/cmd/README.md` is updated.

Check result: hyperfine, 30 runs, macOS, 16 cores, on a loaded host (load average 25–36 throughout): `rtok run -- true` 162.8 ± 13.2 ms with `-lc` versus 17.0 ± 0.8 ms with `-c` (9.6× faster); the bare shells measured `zsh -lc true` 151.6 ms versus `zsh -c true` 3.3 ms. The idle figures are the ones in the card (`rtok run -- true` 0.16 s, `zsh -lc true` 0.15 s, `zsh -c true` 0.00 s); the host never went idle during this session to repeat them. `just check` green.

Status: done 2026-09-24
Model: Claude Code / claude-opus-5-5
