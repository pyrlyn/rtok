Do: After successful `rtok agents install|uninstall <host>` config writes, ask whether to restart that host. Yes → stop then start; No → leave alone. Config `[setup].restart_prompt_timeout_seconds` defaults to `0` (wait forever); positive → silence = No. Interactive TTY shows a left in-place spinner (`⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏` ~80ms, ASCII `-\|/` fallback). Skip under `--dry-run` and non-TTY stdin.

Check: yes/no/timeout-silence→no / `0` no auto-no / spinner clear / dry-run skip; `just check`.

Check result (2026-09-21): `just check` green — 1054 passed, 3 skipped. Desktop apps (e.g. Cursor/Claude `.app`) get quit+reopen on macOS; CLI-only hosts stop binaries and tell the user to relaunch manually.
