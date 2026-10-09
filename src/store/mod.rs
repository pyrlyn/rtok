// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! The SQLite store, re-exported from `rtok-store`. Call sites keep `crate::store`.
//!
//! Housekeeping warnings are printed here. The store crate returns them and writes nothing
//! to the terminal.

pub use rtok_store::*;

/// Show the migration spinner on a terminal. [`crate::cli::run`] calls this before any open.
pub fn install_migrate_spinner() {
    rtok_store::on_long_migrate(|msg, f| crate::progress::with_loader(msg, f));
}

/// `[plugins.memory.embed]` as the plain settings the store accepts.
pub fn embed_settings(embed: &crate::config::MemoryEmbed) -> rtok_store::EmbedSettings {
    rtok_store::EmbedSettings {
        enabled: embed.enabled,
        provider: embed.provider.clone(),
        model: embed.model.clone(),
        dimensions: embed.dimensions,
        hybrid: embed.hybrid,
    }
}

/// T352: run [`Store::housekeeping`] on a background thread, so the `initialize` handshake
/// (mcp) or the listener (proxy) is never delayed by it. The thread dies with the process;
/// one that is killed mid-run only rolls back its current batch (or its `VACUUM`).
pub fn spawn_retention(cfg: &crate::config::Config, surface: &'static str) {
    let job = rtok_store::RetentionJob {
        db_path: cfg.core.db_path.clone(),
        retain_calls_days: cfg.core.retain_calls_days,
        retain_hook_bodies_days: cfg.core.retain_hook_bodies_days,
    };
    let log_path = cfg.log.path.clone();
    let max_bytes = cfg.log.max_bytes;
    let files = cfg.log.files;
    let level = cfg.log.level.clone();
    let spawned = std::thread::Builder::new()
        .name("rtok-retention".into())
        .spawn(move || {
            for warning in rtok_store::Store::housekeeping(&job) {
                eprintln!("rtok {surface}: {}", warning.message);
                crate::logfile::append(
                    &log_path,
                    max_bytes,
                    files,
                    &level,
                    "warn",
                    surface,
                    "retention",
                    &warning.message,
                );
            }
        });
    if let Err(e) = spawned {
        eprintln!("rtok {surface}: retention thread not started: {e}");
    }
}

/// `(archive_id, ts)` for a prior read in `session`. A free function so `use crate::store`
/// names this file: the graph ranker resolves `get_read_cache` through that import. The
/// query itself is [`Store::get_read_cache`].
pub fn get_read_cache(
    store: &Store,
    session: &str,
    path: &str,
) -> rtok_store::Result<Option<(Option<String>, i64)>> {
    store.get_read_cache(session, path)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// `%`, `_` and case are literal in the prefix compare — `LIKE` treated them as a pattern
    /// and dropped cached reads of other files.
    #[test]
    fn clear_read_cache_drops_only_that_path_and_its_mode_keys() {
        let store = Store::open_in_memory().unwrap();
        let claude = store.host_id("claude").unwrap();
        for s in ["s", "other"] {
            store
                .upsert_session(s, claude, None, None, Some("hook"))
                .unwrap();
        }
        let gone = ["/a/x_1.rs", "/a/x_1.rs\tmap", "/a/x_1.rs\tlines\t1-9"];
        let kept = [
            "/a/xa1.rs\tmap",
            "/a/X_1.RS\tmap",
            "/a/x_1.rsx",
            "/a/%\tmap",
        ];
        for p in gone.iter().chain(&kept) {
            store.put_read_cache("s", p, "h", None).unwrap();
        }
        store
            .put_read_cache("other", "/a/x_1.rs", "h", None)
            .unwrap();
        store.clear_read_cache("s", "/a/x_1.rs").unwrap();
        store.clear_read_cache("s", "/a/%").unwrap();
        let cached = |s: &str, p: &str| get_read_cache(&store, s, p).unwrap().is_some();
        for p in gone {
            assert!(!cached("s", p), "{p:?} should be cleared");
        }
        for p in &kept[..3] {
            assert!(cached("s", p), "{p:?} is another file");
        }
        assert!(
            !cached("s", "/a/%\tmap"),
            "a literal `%` path clears its own keys"
        );
        assert!(cached("other", "/a/x_1.rs"), "other sessions keep theirs");
    }
}
