Confirmed by the actual redaction helper: [collect](src/plugins/graph/export.rs#L335) copies member names into meta.scope, but [redact](src/plugins/graph/export.rs#L351) rewrites projects[].name without touching meta.scope. With home /Users/alice and project alias alice, the default export reports `projects[0].name = <user>` and meta.redacted = true while meta.scope still contains alice. The page's [download name](src/plugins/graph/export.rs#L587) independently uses the original scope name, yielding rtok-graph-alice-overview.json even when export contents are redacted. T329.16/T329.40 promise default redaction and downloads but do not track these remaining copies.

Execution plan:

1. Add the username-as-project-alias fixture and assert the complete serialized redacted export contains no original alias; add a page-file assertion for its suggested download name.
2. Apply the same redaction policy to scope labels and derive the download name from a redacted display identity. Keep IDs, edges and non-sensitive names stable.
3. Cover CLI default output, MCP, page downloads, saved-export re-rendering and explicit CLI no-redact; verify JSON and image metadata use consistent labels.

Check: neither redacted payload metadata nor the suggested filename exposes the username alias, explicit no-redact retains its documented behavior, and `just check` passes. The scope leak was reproduced with the actual helper; the filename leak is source-confirmed and its end-to-end page regression belongs to this fix.

Result: One shared `redactor()` now redacts both `meta.scope` and the suggested download name; `meta.focus` holds a symbol, not a path, and is left alone.

Check: `a_project_named_after_the_user_is_hidden_in_scope_and_download_name` failed before and passes after.

Status: done 2026-10-12
Model: Claude Code / claude-sonnet-5-5
