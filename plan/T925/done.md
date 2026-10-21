# T925. Done

On Unix, `PluginLink` now installs an owned copy instead of a symlink when the plugin source
sits under a versioned ketch store directory (`…/store/<package>/v<version>/…`). An upgrade
removes that directory, so the old link into it dangled and Cursor loaded nothing. A link made
earlier into such a directory counts as not up to date, so the next `agents install` swaps it for
a copy. This covers every host installed through `PluginLink` (Cursor, OpenCode, pi, Kilo, MiMo,
ZCode, omp, Antigravity, Command Code, VS Code, Cline hook links), not Cursor alone.

`rtok doctor` reports each such plugin whose link target is gone, or that still links into a
versioned store, as a `stale-plugin` finding for its host, with `rtok agents install <host>` as
the fix. The check is Unix only (a Windows install is already a copy).

Files: `crates/rtok-agent-sdk/src/lib.rs` (`in_versioned_store`, `PluginLink::health`,
`LinkHealth`), `src/agents/plugin.rs` (`all()`, `HostPlugin::problem`), `src/doctor/hooks.rs`
(`host_plugin_links`), `src/agents/cursor/mod.rs` (the Windows-only hook-line fix no longer
reaches a Unix copy).

Checked: SDK tests install from a fake ketch store, remove the old version and still find the
plugin; replace an existing link into a store with a copy; classify dangling / foreign / absent
links. A doctor test with a dangling Cursor link. `just check`.

PR: https://github.com/pyrlyn/rtok/pull/1236
