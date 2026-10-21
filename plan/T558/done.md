Confirmed by an actual-source function probe: [plugin_root](src/plugins/guard/skill.rs#L116) first looks for the current project or a user installation, then accepts any single remaining installation. A manifest containing only `scope: "project", projectPath: "/A", installPath: "/private-A-plugin"` returns that path even for cwd /B. With skill digest enabled and a sufficiently large file there, the guard archives A's skill body and denies B's invocation using the wrong installation. The single-entry fallback intended for legacy unscoped records does not distinguish an explicitly foreign project. T62.1's existing legacy and multi-install tests do not cover this case.

Execution plan:

1. Add a sole foreign-project installation fixture and assert resolution fails open; include a large skill file to prove no archive/denial is generated for it.
2. Restrict the legacy fallback to records without contradictory scope/project metadata; never select an explicitly nonmatching project just because it is the only entry.
3. Retain matching-project precedence, user fallback, genuine legacy single entries and ambiguous-marketplace fail-open behavior.

Check: an unrelated project cannot supply the denied skill's body, supported matching/legacy cases still resolve, and `just check` passes. The probe tested path selection; the full digest check belongs to the fix.

Result: The single-install fallback in `plugin_root` applies only to a record with no `scope` and no `projectPath`.

Check: `a_sole_install_scoped_to_another_project_falls_open`.

Status: done 2026-10-12
Model: Claude Code / claude-sonnet-5-5
