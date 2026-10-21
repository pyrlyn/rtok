**T14.4 `archive` design** · T14.0 · `src/plugins/archive/PLAN.md`
Do: survey live-context shrinking: the three retired archivers plus at least one outside source (provider-native context editing / tool-result clearing, or a published context-management study). Answer: what head/tail and age/size thresholds keep expand rate < 5 %, and when is a provider-native mechanism strictly better than rewriting the request ourselves? Decide the pointer format and the determinism rule per `tool_use_id`.
Check: shared Check; `Target:` is the P5-compress gate number (context-token-turns fall, expand rate ceiling).
Status: done 2026-09-02 · `src/plugins/archive/PLAN.md`. Deterministic expand per tool_use_id; prefer native context editing. Target: CTT −15 %, expand < 5 % (P5 compress).
