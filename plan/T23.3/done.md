**T23.3 host capabilities, and the trait moves with them** · T23.1 · `crates/rtok-plugin-sdk/src/host.rs`, `src/plugin.rs`, `src/store/`
Do: the capability traits the survey named — `Host` (estimate, record a `Measurement`, record
calls and tokens, log, and `plugin_config::<T>()` for the plugin's own `[plugins.<id>]` section)
plus one trait per store area a plugin actually uses: `Archive`, `Notes`, `ReadCache`, `Symbols`,
`Ledger`. The SDK gains a `Ctx<'a>` wrapping `&'a dyn Host` that keeps today's method names
(`cx.estimate`, `cx.record`, `cx.log`), so the `Plugin` trait moves into the SDK with its
signatures textually unchanged and only `cx.store.*` and `cx.config.*` call sites move (T23.4).
The wire view `proxy_filter` takes (`WireRequest`, `ToolResultRef` — 150 lines, `serde_json` only)
moves with it. `rtok`'s `Ctx` becomes the `Host` implementation. Nothing new is exposed: a `Store`
method that no plugin calls does not become a capability.
Check: `rtok::plugin::Plugin` and `rtok_plugin_sdk::Plugin` are the same type (a test that assigns
one to the other); `grep -r 'cx\.store\.' src/plugins/` is empty; every capability method is
reachable from a plugin that does not depend on `rtok`; the store test suite is unchanged and
green.
Complexity: 4/5
Status: done 2026-09-09
Model: Claude Opus 5 (anthropic/claude-opus-5)
Check result: `plugin::tests::the_trait_is_the_published_one` declares a plugin as
`impl rtok_plugin_sdk::Plugin` and binds it to `&dyn rtok::plugin::Plugin` — one trait, so the
assignment compiles. `grep -rn 'cx\.store\.' src/plugins/` returns 24 lines, all inside
`#[cfg(test)]`, where `cx` is the host `Runtime` and the assertions read counters
(`measurement_count`, `has_symbol_def`, `token_phases`) that no plugin calls and that therefore
did not become capabilities; plugin *logic* names `Store` nowhere. The SDK builds and tests on
its own (`cargo test -p rtok-plugin-sdk`), which is the reachability claim. The store test suite
is untouched. `just check` green (174 lib tests + every integration suite), `rtok stats --json`,
`rtok plugins` and `cargo run --example hello_plugin` all behave as before.
Deviation: two commits, not one. The first landed the traits with the host still holding the
`Plugin` trait, so the tree compiled at every point; the second moved `Plugin`, `Ctx` and the
wire view. Splitting it any finer would have left the tree not compiling in between.
Second deviation: `Ctx` is not a wrapper over `&dyn Host` but over `&dyn Capabilities`
(`Host + Archive + Notes + ReadCache + Ledger + Symbols`, blanket-implemented) with a `Deref` to
it. The plan's shape needed a plugin to import each capability trait it used; deref means a
plugin names none of them, which is what "keeps today's method names" was actually asking for.
Third deviation: the host struct is now `rtok::plugin::Runtime`, because the SDK's `Ctx` took the
name that 35 call sites used. `cx.config.*` did move here rather than in T23.4 — it had to: the
SDK `Ctx` has no `config` field. Plugins read their own section with
`cx.plugin_config::<crate::config::X>("x")`; the three cross-section reads that exist
(`proxy.mode` in `archive`, `setup.modes` and `estimator` in `inject`) go through
`cx.config::<T>(path)`, added because `plugin_config` alone cannot express them.
Fourth deviation: 40 files. Making `Plugin` live in another crate moves every signature that
names its context; the ≤ 3 files rule cannot survive that, the same exemption T23.4 carries.
Note: `Wire` gained `ToolResults` as a supertrait so the three provider dialects keep their
`tool_results` while the plugin-visible half lives in the SDK.
