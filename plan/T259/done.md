Batch, Flex and model routing already shipped as T385.1, T385.4, T385.5, T385.10 and T385.12.1–T385.12.2. `docs/batch-flex.md` (and `docs/ru`, `docs/uk`), `architecture.md` §12 and `src/plugins/proxy/AGENTS.md` still described observe, result usage, Flex injection and the semantic-cache skip as planned, and the config sketch listed keys the binary does not load (`enabled`, `observe`, `sticky`).

Do: describe the shipped behaviour. Batch paths stay off every `Wire`, keep their bytes, and are tagged `api_request:batch`; `[proxy.batch] parse_results` writes usage after the forward. Flex is `[proxy.lanes.<lane>] flex` plus `[proxy.flex]`. The batch lane leaves `semantic_cache` false. The loaded keys replace the sketch. `sticky` stays planned and is not a key. `docs/config.md` already documented the loaded keys, so it did not change. The roadmap acceptance boxes for the shipped behaviour are checked. The Mac CI box stays open until this change's macOS check is green. Epic T250 stays in `roadmap.md` (even id; its overview still says the tables are unloaded).

Check: docs prose matches behaviour; `just check`; no sync→Batch.

Result: the three language copies, the architecture table and the proxy agent notes match the code. No product code changed.

Status: done 2026-10-19
