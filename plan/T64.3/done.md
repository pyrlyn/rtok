**T64.3 Prompt-cache FAQ with the measured hit rate per surface** · - · `docs/prompt-cache.md`, `docs/comparison.md`, `README.md`, `site/content/docs/reference/_content.gotmpl`
Do: a `docs/` FAQ section "Does rtok break the prompt cache?" states it per surface with a measured number each — hooks filter once and the host stores the result in its transcript; `inject` byte-stable per turn (tests named); `archive`/`proxy` rewrite only outside `keep_turns` with byte-identical pointers (tests named, hit rate measured and dated); `guard` denials add no bytes; README links the section and `docs/comparison.md` §"The platform itself" points at it instead of restating the number.
Check: the four cited test names verified present (`three_500_budget_800_drops_one_and_is_byte_stable`, `session_start_has_nudges_once_and_stable`, `six_turn_fixtures_archive_only_turns_1_and_2_on_every_wire`, `live_blobs_shrink_stably_outside_the_working_edge`); README link present; `just readme-check` exit 0.
Complexity: 1/5 — one page, one pointer, one link.
Status: done 2026-09-18
Check result: commit 869baa7. Page cites `hit=97.5%` overall and `95.2%` codex (`rtok stats`, 2026-09-18, commands quoted) and zero cache-bust rows from `rtok report`'s Cache section on this machine.
Model: ZCode / GLM-5.3-Flash
