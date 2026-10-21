From the Empryo study (2026-10-02; idea-only, clean-room — Empryo is BSL 1.1, no code copied; Empryo `src/core/intelligence/repo-map.ts` @ `669ff91` was read for the idea only). Today a common name (`new`, `run`, `parse`) returns every same-named definition and every reference to any of them; `annotate_ambiguous` only says "ambiguous", and `impact_bfs` walks all of them. Resolve an edge by (a) whether the referencing file imports the defining file and (b) the IDF of the name, `ln(N_files / df)`, and drop names referenced in more than ~5% of files from ranking. The full import path is stored in `scope` on `import` rows (no new column).

Rank: imported candidate first, then same directory, then the rest, ties by IDF. `callers` / `explore` print the top candidate group and a `+N other definitions of <name>` line. `impact_bfs` follows only edges whose definition is the resolved one, and every edge when nothing resolves. IDF per root is one `GROUP BY name` over `symbols`, computed at index end.

Done when: for an ambiguous name with an import-resolvable definition, `callers` and `impact` list only that definition's references first and name the rest in one line; unresolvable names behave as today.

Check: `tests/fixtures/graph_truth.toml` has 10 ambiguous names defined in two crates; callers precision on those +20 pp vs the unfiltered ref-file list, recall drop ≤ 2 pp, T8.8 overall score ≥ 0.93; impact output bytes on that set −30%; `just check`.

Result: callers precision 1.000 vs 0.296 (+70.4 pp), recall drop 0.000, impact bytes 19424/46623 (0.417), T8.8 overall 0.979. `just check`: 2483 passed, 6 skipped.
