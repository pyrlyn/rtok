`insert_proxy` overwrote a pre-existing `model_provider` (e.g. `"openai"`) and printed "revert: set model_provider to openai", but `strip_proxy` deleted the key instead, and removed `[model_providers.rtok]` even after the user edited it.

Plan: in `src/agents/codex/mod.rs`, `insert_proxy` keeps the replaced value as a trailing comment on the `model_provider` line it owns (`# rtok: was "openai"`, survives re-install); `strip_proxy` restores it when present, and compares `[model_providers.rtok]` against what rtok writes, routing a changed table through `rtok_agent_sdk::keep_edited` (leave + note unless `--yes`). Tests next to `proxy_apply_is_idempotent_and_remove_strips`.

Check: `just check`.

Result: install then remove over `model_provider = "openai"` gives the original file back byte for byte (`proxy_remove_restores_the_replaced_model_provider`, re-install over rtok's own value included); an edited `[model_providers.rtok]` stays with `model_provider` and a `leave …` note unless `--yes` (`proxy_remove_leaves_a_hand_edited_provider_table`). `judge_owned` itself does not fit: its "runs the rtok binary" gate calls every provider table "not rtok's", so the check uses its ownership half, `keep_edited`. A proxy URL changed in rtok's config since install also reads as "changed by you" on remove.

Model: Claude Code / claude-opus-5-5
