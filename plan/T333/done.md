In the plan, T271 (plan.md@966f067 lines 138-139, table row line 17, P1 todo) says doctor must warn on "a desktop `mcpServers.rtok` entry while `code_serves_mcp` is true" and that every install/update path "also runs the desktop removal" (`unregister_mcp_ours`), with the fix `rtok agents install claude --desktop`; done task T171 (done.md:6548) ships the same check as a `duplicate:` line. T275 (plan.md@966f067 lines 171-172, "replaces T271's 'sweep' item") and D33 (plan.md@966f067 line 709) say "Install and update always write rtok's MCP entry into each agent's own config" and the plugin ships no MCP server. These contradict each other because T271 still asks rtok to remove and flag as an error the very entry D33 requires (the one Claude Desktop chat needs), while T271 stays an open P1 task.

Goal: research both approaches, compare trade-offs, recommend one, then update the conflicting tasks. Do not change either task before the decision.

Check: the recommendation and the chosen approach are recorded in this card (or as a decision row), and every task named above is updated so the plan no longer contradicts itself.

Result (2026-10-07, creator decision): D33/T275 wins. Install and update keep writing the desktop `mcpServers.rtok` entry and nothing flags it as a duplicate; T271 is closed as superseded. No code change: the T171 desktop `duplicate:` line is already gone from `src/doctor.rs`. T332 (the `doctor --fix` keep rule for rtok's own entry) is a separate question and stays open; T331.10 now waits on T332 only.

Status: done 2026-10-07
Model: Claude Code / claude-opus-5-5
