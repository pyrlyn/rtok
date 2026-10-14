// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! Warp installer (`rtok agents install warp`, plan T413.12).
//!
//! Warp (the terminal's agent) reads file-based MCP servers from `~/.warp/.mcp.json`, on by
//! default, under `mcpServers`; a local server is `{command, args, env}` and no `type` key is
//! documented, so this host calls [`super::register_stdio_mcp`] (the shape Cline, Roo Code and
//! Windsurf share) instead of a second copy. `[setup.warp] mcp_path` moves the file. A project
//! `.warp/.mcp.json` is the repository's and setup never writes it. Servers added in the app's
//! own MCP settings live in Warp's database, not in a file, and stay untouched.
//!
//! The docs name no hook surface, so hooks stay out; `research.md` section 32.12 has the sources.

use std::path::PathBuf;

use anyhow::Result;

use super::{
    Agent, Kind, Support, Variant, register_stdio_mcp, support_mcp_only, unregister_stdio_mcp,
};
use crate::config::Config;

/// Warp: the desktop terminal (no CLI is documented).
pub struct Warp;

const HOST: &str = "warp";

static VARIANTS: [Variant; 1] = [Variant {
    kind: Kind::Desktop,
    name: "Warp",
    // No bin: `warp-terminal --version` is not documented and would start the GUI. Only the
    // macOS bundle location is documented; Windows and Linux install paths are not.
    bins: &[],
    apps: &["/Applications/Warp.app"],
}];

/// `[setup.warp] mcp_path`, by default `~/.warp/.mcp.json`.
pub fn mcp_path(cfg: &Config) -> PathBuf {
    cfg.setup.warp.mcp_path.clone()
}

/// `mcpServers.rtok` in Warp's user-level MCP file.
pub fn register_mcp(cfg: &Config) -> Result<String> {
    register_stdio_mcp(cfg, &mcp_path(cfg), HOST)
}

/// Drop `mcpServers.rtok` (`rtok agents remove warp`).
pub fn unregister_mcp(cfg: &Config) -> Result<String> {
    unregister_stdio_mcp(cfg, &mcp_path(cfg), HOST)
}

impl Agent for Warp {
    super::agent_identity!(HOST);

    fn support(&self, _kind: Kind, module: &str) -> Support {
        support_mcp_only(
            module,
            "Warp documents no hook surface",
            "Warp documents no setting that points its agent at another endpoint",
            "Warp documents no plugin format; rtok ships no Warp plugin",
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
        super::super::test_scratch_cfg("warp", name, ".mcp.json", dry, |c, path| {
            c.setup.warp.mcp_path = path;
        })
    }

    #[test]
    fn the_default_path_is_the_warp_user_file() {
        let c = Config::default();
        assert!(
            mcp_path(&c).ends_with(".warp/.mcp.json"),
            "{:?}",
            mcp_path(&c)
        );
    }

    #[test]
    fn dry_run_names_the_file_and_creates_nothing() {
        let (c, path) = cfg("dry", true);
        let out = register_mcp(&c).unwrap();
        assert!(out.starts_with("mcpServers.rtok: "), "{out}");
        assert!(out.contains("mcp --host warp"), "{out}");
        assert!(!path.exists());
        assert!(Warp.installed(&c, Kind::Desktop).is_empty());
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
            || Warp.installed(&c, Kind::Desktop),
        );
        let _ = fs::remove_dir_all(path.parent().unwrap());
    }

    #[test]
    fn apply_is_idempotent_via_the_agent_trait() {
        let (c, path) = cfg("apply", false);
        super::super::assert_mcp_only_apply_idempotent(&Warp, &c, Kind::Desktop, "mcpServers");
        let _ = fs::remove_dir_all(path.parent().unwrap());
    }
}
