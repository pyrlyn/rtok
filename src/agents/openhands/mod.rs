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
    Agent, Kind, Mode, Support, Variant, apply_mcp_only, installed_mcp_only, register_stdio_mcp,
    support_mcp_only, unregister_stdio_mcp,
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
    fn id(&self) -> &'static str {
        HOST
    }

    fn variants(&self) -> &'static [Variant] {
        &VARIANTS
    }

    fn readme(&self) -> &'static str {
        include_str!("README.md")
    }

    fn support(&self, _kind: Kind, module: &str) -> Support {
        support_mcp_only(
            module,
            "OpenHands documents hooks only as a project `.openhands/hooks.json`; no user-level file is documented",
            "rtok has not checked OpenHands's LLM base-URL setting, so it writes none",
            "rtok ships no OpenHands plugin",
        )
    }

    fn files(&self, cfg: &Config, _kind: Kind) -> Vec<PathBuf> {
        vec![mcp_path(cfg)]
    }

    fn installed(&self, cfg: &Config, _kind: Kind) -> Vec<&'static str> {
        installed_mcp_only(&mcp_path(cfg))
    }

    fn apply(&self, cfg: &Config, _kind: Kind, mode: Mode) -> Result<Vec<String>> {
        apply_mcp_only(cfg, mode, register_mcp, unregister_mcp)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::agents::assert_stdio_mcp_roundtrip;
    use rtok_agent_sdk::NO_CHANGES;
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
        let lines = OpenHands.apply(&c, Kind::Cli, Mode::Install).unwrap();
        assert!(lines[0].starts_with("mcpServers.rtok: "), "{lines:?}");
        assert_eq!(
            OpenHands.apply(&c, Kind::Cli, Mode::Install).unwrap(),
            [NO_CHANGES]
        );
        assert_eq!(
            OpenHands.apply(&c, Kind::Cli, Mode::Remove).unwrap(),
            ["- mcpServers.rtok"]
        );
        assert_eq!(
            OpenHands.apply(&c, Kind::Cli, Mode::Remove).unwrap(),
            [NO_CHANGES]
        );
        let _ = fs::remove_dir_all(path.parent().unwrap());
    }
}
