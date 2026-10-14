// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! Factory Droid installer (`rtok agents install droid`, plan T413.3).
//!
//! Droid keeps user-level MCP servers in `~/.factory/mcp.json` under `mcpServers`; a stdio
//! server is `{command, args, env}` and `type` is optional for stdio, so this host calls
//! [`super::register_stdio_mcp`] (the shape Cline, Roo Code and Windsurf share) instead of a
//! second copy. `[setup.droid] mcp_path` moves the file. Project `.factory/mcp.json` files are
//! the user's and setup never writes them.
//!
//! Hooks are documented (Claude-shaped events in `~/.factory/hooks.json`, else the `hooks` key of
//! `settings.json`) but stay out until Droid's tool names and `PreToolUse` output are checked
//! against a hook mapping; `research.md` section 32.3 has the sources.

use std::path::PathBuf;

use anyhow::Result;

use super::{
    Agent, Kind, Support, Variant, register_stdio_mcp, support_mcp_only, unregister_stdio_mcp,
};
use crate::config::Config;

/// Factory Droid: the `droid` CLI.
pub struct Droid;

const HOST: &str = "droid";

static VARIANTS: [Variant; 1] = [Variant {
    kind: Kind::Cli,
    name: "Factory Droid",
    bins: &["droid"],
    apps: &[],
}];

/// `[setup.droid] mcp_path`, by default `~/.factory/mcp.json`.
pub fn mcp_path(cfg: &Config) -> PathBuf {
    cfg.setup.droid.mcp_path.clone()
}

/// `mcpServers.rtok` in Droid's user-level MCP file.
pub fn register_mcp(cfg: &Config) -> Result<String> {
    register_stdio_mcp(cfg, &mcp_path(cfg), HOST)
}

/// Drop `mcpServers.rtok` (`rtok agents remove droid`).
pub fn unregister_mcp(cfg: &Config) -> Result<String> {
    unregister_stdio_mcp(cfg, &mcp_path(cfg), HOST)
}

impl Agent for Droid {
    super::agent_identity!(HOST);

    fn support(&self, _kind: Kind, module: &str) -> Support {
        support_mcp_only(
            module,
            "Droid documents hooks, but rtok has no checked mapping of its tool names and PreToolUse output yet",
            "Droid has no single model base-URL setting; each customModels entry carries its own baseUrl",
            "Droid plugins install into its own cache through `droid plugin install`; rtok ships no Droid plugin",
        )
    }

    super::mcp_only_file!(mcp_path, register_mcp, unregister_mcp);
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::agents::assert_stdio_mcp_roundtrip;
    use std::fs;

    fn cfg(name: &str, dry: bool) -> (Config, PathBuf) {
        super::super::test_scratch_cfg("droid", name, "mcp.json", dry, |c, path| {
            c.setup.droid.mcp_path = path;
        })
    }

    #[test]
    fn the_default_path_is_the_factory_user_file() {
        let c = Config::default();
        assert!(
            mcp_path(&c).ends_with(".factory/mcp.json"),
            "{:?}",
            mcp_path(&c)
        );
    }

    #[test]
    fn dry_run_names_the_file_and_creates_nothing() {
        let (c, path) = cfg("dry", true);
        let out = register_mcp(&c).unwrap();
        assert!(out.starts_with("mcpServers.rtok: "), "{out}");
        assert!(out.contains("mcp --host droid"), "{out}");
        assert!(!path.exists());
        assert!(Droid.installed(&c, Kind::Cli).is_empty());
        let _ = fs::remove_dir_all(path.parent().unwrap());
    }

    #[test]
    fn mcp_entry_is_stdio_idempotent_and_remove_keeps_foreign() {
        let (c, path) = cfg("mcp", false);
        assert_stdio_mcp_roundtrip(
            &path,
            HOST,
            || register_mcp(&c),
            || unregister_mcp(&c),
            || Droid.installed(&c, Kind::Cli),
        );
        let _ = fs::remove_dir_all(path.parent().unwrap());
    }

    #[test]
    fn apply_is_idempotent_via_the_agent_trait() {
        let (c, path) = cfg("apply", false);
        super::super::assert_mcp_only_apply_idempotent(&Droid, &c, Kind::Cli, "mcpServers");
        let _ = fs::remove_dir_all(path.parent().unwrap());
    }
}
