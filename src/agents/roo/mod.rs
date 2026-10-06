// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! Roo Code installer (`rtok agents install roo`, plan T413.1).
//!
//! Roo Code is a VS Code extension forked from Cline. Its global MCP file is
//! `mcp_settings.json` under the extension's VS Code globalStorage `settings/`
//! directory (`rooveterinaryinc.roo-cline`), and a stdio server is
//! `mcpServers.<name> = {command, args}` with no `type` — the same JSON Cline
//! writes, so this host calls [`super::register_stdio_mcp`] instead of a second
//! copy. A project `.roo/mcp.json` overrides a same-named global server; setup
//! does not write it. `roo-cline.customStoragePath` moves the settings dir;
//! `[setup.roo] mcp_path` is the override when that setting is in use. The `roo`
//! CLI exists, but no separate MCP path for it is documented, so there is no CLI
//! variant. Shell hooks are not documented either.

use std::path::PathBuf;

use anyhow::Result;

use super::{
    Agent, Kind, Mode, Support, Variant, apply_mcp_only, installed_mcp_only, register_stdio_mcp,
    support_mcp_only, unregister_stdio_mcp,
};
use crate::config::Config;

/// Roo Code: the VS Code extension. The CLI has no documented MCP file of its own.
pub struct Roo;

/// Lowercased `publisher.name` from Roo's `src/package.json` (`RooVeterinaryInc` +
/// `roo-cline`). VS Code's globalStorage folder is that id.
const EXT_ID: &str = "rooveterinaryinc.roo-cline";

const HOST: &str = "roo";

static VARIANTS: [Variant; 1] = [Variant {
    kind: Kind::Desktop,
    name: "Roo Code",
    bins: &[],
    apps: &[
        "/Applications/Visual Studio Code.app",
        "$LOCALAPPDATA/Programs/Microsoft VS Code/Code.exe",
    ],
}];

/// Global `mcp_settings.json`. An empty `[setup.roo] mcp_path` means the VS Code
/// user dir (the `vscode` host override, else the OS default) plus Roo's
/// globalStorage settings file.
pub fn mcp_path(cfg: &Config) -> PathBuf {
    if !cfg.setup.roo.mcp_path.as_os_str().is_empty() {
        return cfg.setup.roo.mcp_path.clone();
    }
    let user_dir = if cfg.setup.vscode.code_user_dir.as_os_str().is_empty() {
        super::vscode::default_user_dir(false)
    } else {
        cfg.setup.vscode.code_user_dir.clone()
    };
    user_dir.join(format!("globalStorage/{EXT_ID}/settings/mcp_settings.json"))
}

/// `mcpServers.rtok` in Roo's global MCP file.
pub fn register_mcp(cfg: &Config) -> Result<String> {
    register_stdio_mcp(cfg, &mcp_path(cfg), HOST)
}

/// Drop `mcpServers.rtok` (`rtok agents remove roo`).
pub fn unregister_mcp(cfg: &Config) -> Result<String> {
    unregister_stdio_mcp(cfg, &mcp_path(cfg), HOST)
}

impl Agent for Roo {
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
            "Roo Code's docs describe mcp_settings.json and .roo/mcp.json, not shell hook events",
            "Roo Code has no documented model base-URL file; roo-cline.debugProxy is a debug proxy, not the model endpoint",
            "Roo Code has no documented local plugin directory to link; modes live in the extension",
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
        super::super::test_scratch_cfg("roo", name, "mcp_settings.json", dry, |c, path| {
            c.setup.roo.mcp_path = path;
        })
    }

    #[test]
    fn empty_mcp_path_uses_the_vscode_global_storage_file() {
        let c = Config::default();
        assert!(c.setup.roo.mcp_path.as_os_str().is_empty());
        assert_eq!(
            mcp_path(&c),
            super::super::vscode::default_user_dir(false)
                .join(format!("globalStorage/{EXT_ID}/settings/mcp_settings.json"))
        );
    }

    #[test]
    fn dry_run_names_the_file_and_creates_nothing() {
        let (c, path) = cfg("dry", true);
        let out = register_mcp(&c).unwrap();
        assert!(out.starts_with("mcpServers.rtok: "), "{out}");
        assert!(out.contains("mcp --host roo"), "{out}");
        assert!(!path.exists());
        assert!(Roo.installed(&c, Kind::Desktop).is_empty());
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
            || Roo.installed(&c, Kind::Desktop),
        );
        let _ = fs::remove_dir_all(path.parent().unwrap());
    }

    #[test]
    fn apply_is_idempotent_via_the_agent_trait() {
        let (c, path) = cfg("apply", false);
        let lines = Roo.apply(&c, Kind::Desktop, Mode::Install).unwrap();
        assert!(lines[0].starts_with("mcpServers.rtok: "), "{lines:?}");
        assert_eq!(
            Roo.apply(&c, Kind::Desktop, Mode::Install).unwrap(),
            [NO_CHANGES]
        );
        assert_eq!(
            Roo.apply(&c, Kind::Desktop, Mode::Remove).unwrap(),
            ["- mcpServers.rtok"]
        );
        assert_eq!(
            Roo.apply(&c, Kind::Desktop, Mode::Remove).unwrap(),
            [NO_CHANGES]
        );
        let _ = fs::remove_dir_all(path.parent().unwrap());
    }
}
