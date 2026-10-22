Users and agents had the reference for every `rtok task` command (`docs/tasks.md`) and the `rtok-tasks` skill listed the tools, but nothing said how to write a good task: title, description with a done check, when to split into subtasks, and when to record `dep`, `conflict`, `parallel` and priority.

Check: `tests/skill.rs` (`tasks_skill_links_a_creating_guide_with_only_real_commands`, `docs_on_creating_tasks_with_a_model_name_only_real_commands_and_tools`, `install_adds_the_tasks_skill_and_leaves_a_users_own_skill_alone`); `just check`.

Result: `docs/tasks.md` has a "Creating a task" section (steps, a CLI example, the MCP arguments), translated in `docs/ru` and `docs/uk`. The skill body was at its 2 KB cap, so the workflow lives in `skills/rtok-tasks/creating.md`, installed with the skill directory, and `SKILL.md` links to it; the stale "Disk only" notes on `conflict` and `parallel` went, since GitHub and GitLab keep them as labels. A second section, "Creating tasks with a model", tells a user how to have a coding agent write the tasks through the MCP tools: install once, ask for tasks not code, review first, what the model calls, how to check and fix the result; rtok itself calls no model. `creating.md` tells the agent the same from its side. The test checks the link, that the guide names only real `rtok task` subcommands and flags, and that install copies it.

Status: done 2026-10-19
Model: Claude Code / claude-opus-5-5
