The rest of T329.22 (split on 2026-10-10 because the view and these two parts did not fit one 500-line cap). T329 §8a level 2 on top of `DrillView`:

- Side panel for the selected node: path and line, signature, callers and callees (the other ends of the frame's `calls` edges, each one click from selecting it) and an Open in editor link, `vscode://file/<root>/<path>:<line>`. For an `external` node the link uses the root of the project that owns it, the panel names that project, and an Open in `<project>` button steps into it. A file has no line in its link. With nothing picked the panel follows the focused symbol, so a hit that was just opened is the one shown.
- Search box: Enter sends the text as the request's `query` (typing alone sends nothing); the frame's `hits` are listed with their projects. A hit in the open project focuses its symbol; a hit in another project opens that project with the symbol focused.
- `editorLink` in `drillState.ts` encodes each root and path segment (a `#` or `?` in a path would end the link early) and keeps a Windows drive colon. `Search` gained an optional `onEnter`. `sampleDrill` caps hits at 8 per project like the server and searches the generated project too.
- Docs: "The graph page" section in `docs/commands.md` (en, ru, uk).

Check: Vitest for the panel contents (place, signature, callers and callees, a file), the editor link (including an external node and special characters), and the search request and hits (Enter only, hits with projects, a hit in this project, a hit in another project, no match, clearing); a story on the sample server (select a function, read the panel, search, click a hit in another project; both themes, axe); `just spa-typecheck`, `just spa-stories`, `just spa-e2e`, `just check`.

Result: the panel and search ship in `DrillPanel.tsx` and `DrillView.tsx`, with no protocol change (`query` and `hits` were already in the frame).

Deviations: none from the card. The callers and callees lists show only what the capped picture holds, and say so ("none in this picture").

Status: done 2026-10-10 · Model: Claude Code / sonnet-5.5
