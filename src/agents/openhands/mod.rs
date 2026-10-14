// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! OpenHands CLI installer (`rtok agents install openhands`, plan T413.14).
//!
//! The OpenHands CLI keeps its MCP servers in `~/.openhands/mcp.json` under `mcpServers`; a stdio
//! server is `{command, args, env}` with no `type`, so this host calls
//! [`super::register_stdio_mcp`] (the shape Cline, Roo Code, Windsurf and Droid share) instead of
//! a second copy. `[setup.openhands] mcp_path` moves the file.
//!
//! Hooks stay out: the docs describe them only as a project `.openhands/hooks.json`, which
//! belongs to the project and is never written by setup. `research.md` section 32.14 has the
//! sources.

use std::path::PathBuf;

use anyhow::Result;

use super::{
    Agent, Kind, Support, Variant, register_stdio_mcp, support_mcp_only, unregister_stdio_mcp,
};
use crate::config::Config;

/// OpenHands: the `openhands` CLI.
pub struct OpenHands;

const HOST: &str = "openhands";

static VARIANTS: [Variant; 1] = [Variant {
    kind: Kind::Cli,
    name: "OpenHands",
    bins: &["openhands"],
    apps: &[],
}];

/// `[setup.openhands] mcp_path`, by default `~/.openhands/mcp.json`.
pub fn mcp_path(cfg: &Config) -> PathBuf {
    cfg.setup.openhands.mcp_path.clone()
}

/// `mcpServers.rtok` in OpenHands's user-level MCP file.
pub fn register_mcp(cfg: &Config) -> Result<String> {
    register_stdio_mcp(cfg, &mcp_path(cfg), HOST)
}

/// Drop `mcpServers.rtok` (`rtok agents remove openhands`).
pub fn unregister_mcp(cfg: &Config) -> Result<String> {
    unregister_stdio_mcp(cfg, &mcp_path(cfg), HOST)
}

impl Agent for OpenHands {
    super::agent_identity!(HOST);

    fn support(&self, _kind: Kind, module: &str) -> Support {
        support_mcp_only(
            module,
            "OpenHands documents hooks only as a project `.openhands/hooks.json`; no user-level file is documented",
            "rtok has not checked OpenHands's LLM base-URL setting, so it writes none",
            "rtok ships no OpenHands plugin",
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
        super::super::test_scratch_cfg("openhands", name, "mcp.json", dry, |c, path| {
            c.setup.openhands.mcp_path = path;
        })
    }

    #[test]
    fn the_default_path_is_the_openhands_user_file() {
        let c = Config::default();
        assert!(
            mcp_path(&c).ends_with(".openhands/mcp.json"),
            "{:?}",
            mcp_path(&c)
        );
    }

    #[test]
    fn dry_run_names_the_file_and_creates_nothing() {
        let (c, path) = cfg("dry", true);
        let out = register_mcp(&c).unwrap();
        assert!(out.starts_with("mcpServers.rtok: "), "{out}");
        assert!(out.contains("mcp --host openhands"), "{out}");
        assert!(!path.exists());
        assert!(OpenHands.installed(&c, Kind::Cli).is_empty());
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
            || OpenHands.installed(&c, Kind::Cli),
        );
        let _ = fs::remove_dir_all(path.parent().unwrap());
    }

    #[test]
    fn apply_is_idempotent_via_the_agent_trait() {
        let (c, path) = cfg("apply", false);
        super::super::assert_mcp_only_apply_idempotent(&OpenHands, &c, Kind::Cli, "mcpServers");
        let _ = fs::remove_dir_all(path.parent().unwrap());
    }
}
