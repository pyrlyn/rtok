T329 §6 (first half), §7 and the tags-backend half of §6a mode 2. Every graph command and graph MCP tool takes `project` (id or path); without it the project is the caller's cwd and its links are in scope (T336: the web UI selection never replaces the cwd). Queries run over the scope as one graph, rows carry `project` (JSON field, `[name]` text prefix), same-named symbols across projects are grouped and flagged ambiguous with the selected project first. Depends on T329.3.

Check: fixture repos from the T329 Check list; `callers` of a function in C returns call sites in A and B labelled by project; `impact` walks up into A; a same-named symbol is grouped and flagged; MCP `project` set to D does not cross; `just check`.

Split (2026-10-06, complexity 3 each): T329.4.1 and T329.4.2 below. This card stays the specification and the parent; it closes with the second subtask.

Closed 2026-10-07 with its last subtask: T329.4.1 (#778) and T329.4.2 (#794).
