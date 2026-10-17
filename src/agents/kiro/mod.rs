// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! Kiro installer, IDE and CLI (`rtok agents install kiro`, plan T413.4).
//!
//! Kiro's MCP page documents one user-level file, `~/.kiro/settings/mcp.json`, with
//! `mcpServers.<name> = {command, args, env, ...}`; the page carries IDE, CLI and Web tabs and a
//! CLI-only Windows note but states no per-surface path, so both variants edit that one file
//! (like Kilo, detection only). Another Kiro page lists `~/.kiro/mcp.json` for the same scope;
//! the configuration page is the reference, and `[setup.kiro] mcp_path` moves the file if that
//! turns out wrong for a build (`research.md` section 32.4). The shared
//! [`super::register_stdio_mcp`] writes the entry (the shape Cline, Roo Code and Windsurf share).
//! Project `.kiro/settings/mcp.json` files are the user's and setup never writes them.
//!
//! Hooks are JSON files under `.kiro/hooks/` whose command output and exit-code rules the hooks
//! page does not state, so there is nothing to build a hook entry against.

use std::path::PathBuf;

use anyhow::Result;

use super::{
    Agent, Kind, Support, Variant, register_stdio_mcp, support_mcp_only, unregister_stdio_mcp,
};
use crate::config::Config;

/// Kiro: the `kiro-cli` CLI and the Kiro IDE.
pub struct Kiro;

const HOST: &str = "kiro";

// Only macOS app paths are probed: the docs name no Windows or Linux install folder.
static VARIANTS: [Variant; 2] = [
    Variant {
        kind: Kind::Cli,
        name: "Kiro CLI",
        bins: &["kiro-cli"],
        apps: &[],
    },
    Variant {
        kind: Kind::Desktop,
        name: "Kiro IDE",
        bins: &[],
        apps: &["/Applications/Kiro.app", "~/Applications/Kiro.app"],
    },
];

/// `[setup.kiro] mcp_path`, by default `~/.kiro/settings/mcp.json`.
pub fn mcp_path(cfg: &Config) -> PathBuf {
    cfg.setup.kiro.mcp_path.clone()
}

/// `mcpServers.rtok` in Kiro's user-level MCP file.
pub fn register_mcp(cfg: &Config) -> Result<String> {
    register_stdio_mcp(cfg, &mcp_path(cfg), HOST)
}

/// Drop `mcpServers.rtok` (`rtok agents remove kiro`).
pub fn unregister_mcp(cfg: &Config) -> Result<String> {
    unregister_stdio_mcp(cfg, &mcp_path(cfg), HOST)
}

impl Agent for Kiro {
    super::agent_identity!(HOST);

    /// Both variants read `~/.kiro/settings/mcp.json`, so one apply covers them.
    fn shared(&self) -> bool {
        true
    }

    fn support(&self, _kind: Kind, module: &str) -> Support {
        support_mcp_only(
            module,
            "Kiro's hooks page does not state the exit-code or output-to-context rules of command hooks",
            "Kiro documents no model base-URL setting",
            "Kiro documents no local plugin directory to link",
        )
    }

    super::mcp_only_file!(mcp_path, register_mcp, unregister_mcp);
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::agents::{Mode, assert_stdio_mcp_roundtrip};
    use std::fs;

    fn cfg(name: &str, dry: bool) -> (Config, PathBuf) {
        super::super::test_scratch_cfg("kiro", name, "mcp.json", dry, |c, path| {
            c.setup.kiro.mcp_path = path;
        })
    }

    #[test]
    fn the_default_path_is_the_kiro_user_settings_file() {
        let c = Config::default();
        assert!(
            mcp_path(&c).ends_with(".kiro/settings/mcp.json"),
            "{:?}",
            mcp_path(&c)
        );
    }

    #[test]
    fn the_ide_variant_is_detected_from_its_app_and_shares_the_cli_file() {
        let v = &Kiro.variants()[1];
        assert_eq!((v.kind, v.name), (Kind::Desktop, "Kiro IDE"));
        assert!(v.bins.is_empty());
        assert!(v.apps.contains(&"/Applications/Kiro.app"));
        assert_eq!(Kiro.variants()[0].bins, ["kiro-cli"]);

        let (c, path) = cfg("shared", false);
        assert_eq!(Kiro.files(&c, Kind::Desktop), Kiro.files(&c, Kind::Cli));
        Kiro.apply(&c, Kind::Desktop, Mode::Install).unwrap();
        assert_eq!(Kiro.installed(&c, Kind::Cli), ["mcp"]);
        let _ = fs::remove_dir_all(path.parent().unwrap());
    }

    #[test]
    fn dry_run_names_the_file_and_creates_nothing() {
        let (c, path) = cfg("dry", true);
        let out = register_mcp(&c).unwrap();
        assert!(out.starts_with("mcpServers.rtok: "), "{out}");
        assert!(out.contains("mcp --host kiro"), "{out}");
        assert!(!path.exists());
        assert!(Kiro.installed(&c, Kind::Cli).is_empty());
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
            || Kiro.installed(&c, Kind::Cli),
        );
        let _ = fs::remove_dir_all(path.parent().unwrap());
    }

    #[test]
    fn apply_is_idempotent_via_the_agent_trait() {
        let (c, path) = cfg("apply", false);
        super::super::assert_mcp_only_apply_idempotent(&Kiro, &c, Kind::Cli, "mcpServers");
        let _ = fs::remove_dir_all(path.parent().unwrap());
    }
}
