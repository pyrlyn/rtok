Split from T441.6: tell every host's agents to plan and track work as rtok tasks, installed through `rtok agents install`, with host configs unchanged except rtok's own entry (T441 §2, §11 milestone 10).

Check: `tests/skill.rs` (`tasks_skill_names_only_commands_rtok_task_has`, `install_adds_the_tasks_skill_and_leaves_a_users_own_skill_alone`) and `mcp::tasks::tests::the_task_skill_names_exactly_these_tools`; CI `gate` green.

Result: the rule line is a new skill, `skills/rtok-tasks/SKILL.md`, added to `SKILLS` in `src/agents/skill.rs`, so the skill sync that already installs `rtok` and `rtok-worktrees` installs and removes it on every host with a documented skill root. Its description is the always-visible rule (rtok tasks, not TODO files, unless `AGENTS.md` or the user names another tracker), and its body maps the five `task_*` MCP tools to their `rtok task` commands. No host config and no repository file is written. A managed block in AGENTS.md/CLAUDE.md was rejected because no install path writes into users' repositories today; MCP `initialize` instructions would be a second, always-on injection. Tests tie the skill to the real CLI flags and the MCP tool list.

Status: done 2026-10-08
Model: Claude Code / claude-sonnet-5-5
