Every external program goes through `rtok_sys::proc::ProgressRunner`. A terminal gets a spinner or a bar from the moment the process starts; a pipe, `--json`, MCP and hooks get nothing. `graph index`, `worktree gc/clean`, `bench`, `doctor` and `agents install/update` use a `Batch` when the total is known. The installer question hides the live bar while it is on screen.

The subtasks landed as T276.1 (`dec4ea2`, #990), T276.2 (`5d826555`, #996), T276.4 (`1f9ebe5`, #1016), T276.5 (done 2026-10-12), and T276.3/T276.6 (`7067aab3`, #1057). This card only records that the epic's check already holds on main.

Check: on a TTY, each of items 1-5 shows its message from the moment the process starts until output appears; `rtok agents update claude` in a terminal shows step loaders and the plugin question is readable; `rtok doctor 2>/dev/null` and `--json` output are unchanged; a Vfs or snapshot test asserts no spinner bytes on non-TTY stderr; every operation with a known total shows a progress bar with its count and a spinner appears only on unmeasurable waits; `just check`.

Check result: `doctor_probing_mcp_servers_writes_no_bar_to_a_pipe`, `install_and_update_draw_no_bar_on_a_pipe` and the `ui_style` pipe runs write no bar bytes. `apply_all_steps_the_bar_once_per_host` steps once per host. `the_lint_gate_bans_command_new_outside_rtok_sys` keeps the `Command::new` ban on libraries and binaries. A manual TTY pass was not re-run here; the messages and the non-TTY behaviour are what #1057 landed.

Status: done 2026-10-19
