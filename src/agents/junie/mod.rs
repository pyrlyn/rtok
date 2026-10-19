// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! JetBrains Junie CLI installer (`rtok agents install junie`, plan T413.9).
//!
//! The CLI reads user-level MCP servers from `~/.junie/mcp/mcp.json`, where a
//! stdio server is `mcpServers.<name> = {command, args}` with no `type` — the
//! JSON Cline and Roo Code write, so this host calls
//! [`super::register_stdio_mcp`] instead of a second copy. The project file
//! `.junie/mcp/mcp.json` is committed with the repository, so setup never writes
//! it. The docs name no variable that moves `~/.junie`; `[setup.junie]
//! mcp_path` is the override. The IDE plugin documents no file path, so there is
//! no IDE variant, and the only documented hook event is `SessionStart`.

use std::path::PathBuf;

use anyhow::Result;

use super::{
    Agent, Kind, Support, Variant, register_stdio_mcp, support_mcp_only, unregister_stdio_mcp,
};
use crate::config::Config;

/// JetBrains Junie: the CLI. The IDE plugin has no documented MCP file.
pub struct Junie;

const HOST: &str = "junie";

static VARIANTS: [Variant; 1] = [Variant {
    kind: Kind::Cli,
    name: "Junie CLI",
    bins: &["junie"],
    apps: &[],
}];

/// User `mcp.json`. An empty `[setup.junie] mcp_path` means `~/.junie/mcp/mcp.json`.
pub fn mcp_path(cfg: &Config) -> PathBuf {
    if !cfg.setup.junie.mcp_path.as_os_str().is_empty() {
        return cfg.setup.junie.mcp_path.clone();
    }
    super::home_dir().join(".junie/mcp/mcp.json")
}

/// `mcpServers.rtok` in Junie's user MCP file.
pub fn register_mcp(cfg: &Config) -> Result<String> {
    register_stdio_mcp(cfg, &mcp_path(cfg), HOST)
}

/// Drop `mcpServers.rtok` (`rtok agents remove junie`).
pub fn unregister_mcp(cfg: &Config) -> Result<String> {
    unregister_stdio_mcp(cfg, &mcp_path(cfg), HOST)
}

impl Agent for Junie {
    super::agent_identity!(HOST);

    fn support(&self, _kind: Kind, module: &str) -> Support {
        support_mcp_only(
            module,
            "Junie documents only a SessionStart hook, and rtok's hooks need tool-use events",
            "Junie has no documented model base-URL file",
            "Junie has no documented local plugin directory to link",
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
        super::super::test_scratch_cfg("junie", name, "mcp.json", dry, |c, path| {
            c.setup.junie.mcp_path = path;
        })
    }

    #[test]
    fn empty_mcp_path_uses_the_user_junie_dir() {
        let c = Config::default();
        assert!(c.setup.junie.mcp_path.as_os_str().is_empty());
        assert_eq!(
            mcp_path(&c),
            super::super::home_dir().join(".junie/mcp/mcp.json")
        );
    }

    #[test]
    fn dry_run_names_the_file_and_creates_nothing() {
        let (c, path) = cfg("dry", true);
        let out = register_mcp(&c).unwrap();
        assert!(out.starts_with("mcpServers.rtok: "), "{out}");
        assert!(out.contains("mcp --host junie"), "{out}");
        assert!(!path.exists());
        assert!(Junie.installed(&c, Kind::Cli).is_empty());
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
            || Junie.installed(&c, Kind::Cli),
        );
        let _ = fs::remove_dir_all(path.parent().unwrap());
    }

    #[test]
    fn apply_is_idempotent_via_the_agent_trait() {
        let (c, path) = cfg("apply", false);
        super::super::assert_mcp_only_apply_idempotent(&Junie, &c, Kind::Cli, "mcpServers");
        let _ = fs::remove_dir_all(path.parent().unwrap());
    }
}
