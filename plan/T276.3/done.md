`run_cli` runs through `ProgressRunner` with a `running <bin> <subcommand>…` label and the Windows shim. `spawn_cli` is gone. `app_version` is a quiet runner with a 3 s limit. `rtok doctor` draws one bar, `probing MCP servers…`, and steps it once per server. The installer question runs inside a pause that hides every live bar, so the host bar stays up on a terminal and the question stays readable.

Landed in `7067aab3` (#1057). This card only records that the check already holds on main.

Check: the T276 card's Check lines for items 1 and 2; a non-TTY test asserts no spinner bytes; `just check`.

Check result: `a_host_cli_call_is_labelled_by_its_subcommand` names only the subcommand (`running claude plugin install…`). `doctor_probing_mcp_servers_writes_no_bar_to_a_pipe` and `install_and_update_draw_no_bar_on_a_pipe` write no bar bytes to a pipe. `apply_all_steps_the_bar_once_per_host` steps the host bar once per host.

Status: done 2026-10-19
