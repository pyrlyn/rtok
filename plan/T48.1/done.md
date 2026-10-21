**T48.1 pi loads the rtok extension from its linked directory** · P0, 2/5 · `plugins/pi/tests/load.test.ts`, `tests/pi_plugin.rs`, `plugins/pi/README.md`

From I-35. The pi docs list only `extensions/*.ts` and `extensions/*/index.ts`, so the `extensions/rtok` link (no `index.ts`) looked unloaded.

Do: read pi 0.85.1's loader (`dist/core/extensions/loader.js`): `discoverExtensionsInDir` follows a symlinked subdirectory and `resolveExtensionEntries` reads its `package.json` `pi.extensions` before `index.ts`. The link already loads, so no `index.ts` was added (it would be dead code). Added a Node test that calls pi's own `discoverAndLoadExtensions` on the linked directory; `tests/pi_plugin.rs` runs it right after `rtok agents install pi --yes` against the real installed dir. README states the real loading rule.

Check: the load test expects no errors, exactly one extension from `extensions/rtok/extensions/rtok.ts` with `tool_call` and `tool_result` handlers; skipped when pi is not on PATH. Negative control: with `pi.extensions` emptied the test fails (0 pass, 1 fail).

Status: done 2026-09-17 · Model: Claude Code / Fable 5.1

Evidence: `cargo test --test pi_plugin` 5 passed; `just check` 716 passed, 2 skipped, jscpd 70 clones.

Deviation: none; the idea's premise (missing `index.ts` breaks loading) was wrong for pi 0.85.1, so the fix is a proof plus docs, not a new entry file.
