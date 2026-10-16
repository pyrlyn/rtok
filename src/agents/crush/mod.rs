// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! Crush installer (`rtok agents install crush`, plan T413.11).
//!
//! Crush's current config is `crushrc`, a Bash script, and the JSON `crush.json` is deprecated
//! but still merged with it. rtok writes the JSON file only: it holds a structured `mcp` object
//! whose `rtok` entry can be added and removed without touching the user's other keys, while a
//! Bash script would need marker lines rtok cannot verify. A server is
//! `mcp.<name> = {command, args}`; `type` defaults to `stdio` in Crush's schema, so the entry
//! carries none, like the other `{command, args}` hosts, and goes through
//! [`super::register_stdio_mcp_in`]. Crush warns only when a `crush.json` and a `crushrc` in
//! one directory define the same top-level key, so a user who also runs `mcp add` in `crushrc`
//! sees a log warning and the two are still merged.
//!
//! The global file is `$CRUSH_GLOBAL_CONFIG/crush.json`, else
//! `$XDG_CONFIG_HOME/crush/crush.json`, else `~/.config/crush/crush.json`; the default in
//! `[setup.crush] config_path` is the last one. Hooks stay unsupported: the only documented
//! event is `PreToolUse` and rtok has no checked mapping of Crush's tool names.

use std::path::PathBuf;

use anyhow::Result;

use super::{
    Agent, Kind, Support, Variant, register_stdio_mcp_in, support_mcp_only, unregister_stdio_mcp_in,
};
use crate::config::Config;

/// Charm's Crush: a terminal agent, one CLI variant.
pub struct Crush;

const HOST: &str = "crush";

/// Crush's top-level object for MCP servers in `crush.json`.
pub(crate) const MCP_KEY: &str = "mcp";

static VARIANTS: [Variant; 1] = [Variant {
    kind: Kind::Cli,
    name: "Crush",
    bins: &["crush"],
    apps: &[],
}];

/// Global `crush.json` (`[setup.crush] config_path`).
pub fn config_path(cfg: &Config) -> PathBuf {
    cfg.setup.crush.config_path.clone()
}

/// `mcp.rtok` in Crush's global JSON config.
pub fn register_mcp(cfg: &Config) -> Result<String> {
    register_stdio_mcp_in(cfg, &config_path(cfg), MCP_KEY, HOST)
}

/// Drop `mcp.rtok` (`rtok agents remove crush`).
pub fn unregister_mcp(cfg: &Config) -> Result<String> {
    unregister_stdio_mcp_in(cfg, &config_path(cfg), MCP_KEY, HOST)
}

impl Agent for Crush {
    super::agent_identity!(HOST);

    fn support(&self, _kind: Kind, module: &str) -> Support {
        support_mcp_only(
            module,
            "Crush documents only a PreToolUse hook, and rtok has no checked mapping of its tool names",
            "Crush takes provider base URLs per provider entry; rtok has no checked single endpoint to point at the proxy",
            "Crush has no documented local plugin directory to link; it loads skills, not plugins",
        )
    }

    super::mcp_only_file!(config_path, register_mcp, unregister_mcp);
}

#[cfg(test)]
mod tests {
    use super::*;
    use rtok_agent_sdk::NO_CHANGES;
    use serde_json::Value;
    use std::fs;

    fn cfg(name: &str, dry: bool) -> (Config, PathBuf) {
        super::super::test_scratch_cfg("crush", name, "crush.json", dry, |c, path| {
            c.setup.crush.config_path = path;
        })
    }

    fn read(path: &std::path::Path) -> Value {
        serde_json::from_str(&fs::read_to_string(path).unwrap()).unwrap()
    }

    #[test]
    fn default_config_path_is_the_xdg_config_file() {
        let c = Config::default();
        assert!(
            config_path(&c).ends_with(".config/crush/crush.json"),
            "{:?}",
            config_path(&c)
        );
    }

    #[test]
    fn dry_run_names_the_file_and_creates_nothing() {
        let (c, path) = cfg("dry", true);
        let out = register_mcp(&c).unwrap();
        assert!(out.starts_with("mcp.rtok: "), "{out}");
        assert!(out.contains("mcp --host crush"), "{out}");
        assert!(!path.exists());
        assert!(Crush.installed(&c, Kind::Cli).is_empty());
        let _ = fs::remove_dir_all(path.parent().unwrap());
    }

    #[test]
    fn entry_is_stdio_without_a_type_and_remove_keeps_every_foreign_key() {
        let (c, path) = cfg("entry", false);
        let before = r#"{"$schema":"https://charm.land/crush.json","options":{"debug":true},"mcp":{"foreign":{"type":"http","url":"https://x"}}}"#;
        fs::write(&path, before).unwrap();
        assert!(register_mcp(&c).unwrap().starts_with("mcp.rtok: "));
        assert_eq!(register_mcp(&c).unwrap(), NO_CHANGES);
        let root = read(&path);
        let ours = &root["mcp"]["rtok"];
        assert!(ours.get("type").is_none(), "{ours}");
        assert_eq!(ours["args"][0], "mcp");
        assert_eq!(ours["args"][2], "crush");
        assert_eq!(root["mcp"]["foreign"]["url"], "https://x");
        assert_eq!(root["options"]["debug"], true);
        assert!(root.get("mcpServers").is_none(), "{root}");
        assert_eq!(Crush.installed(&c, Kind::Cli), ["mcp"]);

        assert_eq!(unregister_mcp(&c).unwrap(), "- mcp.rtok");
        assert_eq!(unregister_mcp(&c).unwrap(), NO_CHANGES);
        assert_eq!(read(&path), read_str(before));
        assert!(Crush.installed(&c, Kind::Cli).is_empty());
        let _ = fs::remove_dir_all(path.parent().unwrap());
    }

    fn read_str(s: &str) -> Value {
        serde_json::from_str(s).unwrap()
    }

    #[test]
    fn apply_is_idempotent_via_the_agent_trait() {
        let (c, path) = cfg("apply", false);
        super::super::assert_mcp_only_apply_idempotent(&Crush, &c, Kind::Cli, "mcp");
        let _ = fs::remove_dir_all(path.parent().unwrap());
    }
}
