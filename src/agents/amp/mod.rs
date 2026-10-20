// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! Amp (Sourcegraph) installer (`rtok agents install amp`, plan T413.5).
//!
//! Amp keeps user-level MCP servers under the dotted top-level key `amp.mcpServers` of
//! `~/.config/amp/settings.json` (or `settings.jsonc`, comments allowed); a stdio server is
//! `{command, args}` and user-level servers need no approval. The file is edited as JSONC so the
//! user's comments survive. `[setup.amp] settings_path` moves it; a `.jsonc` file that is the only
//! one Amp has is edited in place (the `mimo` rule, [`jsonc::lone_sibling`]). Workspace
//! `.amp/settings.json` files are the project's and setup never writes them.
//!
//! The documented plugin API (`.ts`/`.js` files in `~/.config/amp/plugins`) is a different
//! surface from the shell hooks and the OpenCode-shaped plugin rtok ships, so no plugin is
//! linked (`research.md` section 32.5).

use std::path::PathBuf;

use anyhow::Result;
use serde_json::Value;

use super::{Agent, Kind, Mode, Support, Variant, apply_mcp_only, jsonc, support_mcp_only};
use crate::config::Config;

/// Amp: the `amp` CLI (the editor extensions share its settings file).
pub struct Amp;

const HOST: &str = "amp";
/// The settings key is one dotted property name, not a nested `amp` object.
const KEY: &str = super::mcp::AMP_SERVERS;

static VARIANTS: [Variant; 1] = [Variant {
    kind: Kind::Cli,
    name: "Amp",
    bins: &["amp"],
    apps: &[],
}];

/// The file the entry goes into: `[setup.amp] settings_path`, or its `.jsonc` sibling when that
/// is the only one Amp has.
pub fn settings_path(cfg: &Config) -> PathBuf {
    let json = &cfg.setup.amp.settings_path;
    if json.file_name().is_some_and(|n| n == "settings.json") {
        jsonc::lone_sibling(json)
    } else {
        json.clone()
    }
}

fn entry(cmd: &str) -> Value {
    super::stdio_mcp_entry(cmd, HOST)
}

/// `amp.mcpServers.rtok = {command, args}`.
pub fn register_mcp(cfg: &Config) -> Result<String> {
    let cmd = super::rtok_mcp_command();
    jsonc::upsert_entry(cfg, &settings_path(cfg), KEY, "rtok", &entry(&cmd), |_| {
        format!("{KEY}.rtok: {}", super::mcp_summary(&cmd, HOST))
    })
}

/// Drop `amp.mcpServers.rtok` unless the user edited it (T246).
pub fn unregister_mcp(cfg: &Config) -> Result<String> {
    jsonc::remove_entry(cfg, &settings_path(cfg), KEY, "rtok", &entry("rtok"))
}

/// True when the settings file holds an `amp.mcpServers.rtok` entry.
fn installed_mcp(cfg: &Config) -> bool {
    let raw = super::read(&settings_path(cfg));
    jsonc::parse(&raw)
        .ok()
        .is_some_and(|root| root.get(KEY).and_then(|s| s.get("rtok")).is_some())
}

impl Agent for Amp {
    super::agent_identity!(HOST);

    fn support(&self, _kind: Kind, module: &str) -> Support {
        support_mcp_only(
            module,
            "Amp has no shell hook events; its plugin events (tool.call, tool.result) are TypeScript handlers",
            "amp.url is Amp's own server URL, not a model-provider base URL",
            "Amp loads .ts and .js plugins from its plugins directory, but rtok ships no plugin for its API yet",
        )
    }

    fn files(&self, cfg: &Config, _kind: Kind) -> Vec<PathBuf> {
        vec![settings_path(cfg)]
    }

    fn installed(&self, cfg: &Config, _kind: Kind) -> Vec<&'static str> {
        if installed_mcp(cfg) {
            vec!["mcp"]
        } else {
            Vec::new()
        }
    }

    fn apply(&self, cfg: &Config, _kind: Kind, mode: Mode) -> Result<Vec<String>> {
        apply_mcp_only(cfg, mode, register_mcp, unregister_mcp)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use rtok_agent_sdk::NO_CHANGES;
    use std::fs;

    fn cfg(name: &str, dry: bool) -> (Config, PathBuf) {
        super::super::test_scratch_cfg("amp", name, "settings.json", dry, |c, path| {
            c.setup.amp.settings_path = path;
        })
    }

    fn read_json(path: &std::path::Path) -> Value {
        jsonc::parse(&fs::read_to_string(path).unwrap()).unwrap()
    }

    #[test]
    fn the_default_path_is_the_amp_user_settings_file() {
        let c = Config::default();
        assert!(
            settings_path(&c).ends_with(".config/amp/settings.json")
                || settings_path(&c).ends_with(".config/amp/settings.jsonc"),
            "{:?}",
            settings_path(&c)
        );
    }

    #[test]
    fn dry_run_names_the_file_and_creates_nothing() {
        let (c, path) = cfg("dry", true);
        let out = register_mcp(&c).unwrap();
        assert!(out.starts_with("amp.mcpServers.rtok: "), "{out}");
        assert!(out.contains("mcp --host amp"), "{out}");
        assert!(!path.exists());
        assert!(Amp.installed(&c, Kind::Cli).is_empty());
        let _ = fs::remove_dir_all(path.parent().unwrap());
    }

    #[test]
    fn the_entry_sits_under_the_dotted_key_and_foreign_settings_survive() {
        let (c, path) = cfg("entry", false);
        let before = "{\n  // keep me\n  \"amp.mcpServers\": {\"other\": {\"command\": \"x\"}},\n  \"amp.tools.disable\": [\"a\"]\n}\n";
        fs::write(&path, before).unwrap();
        register_mcp(&c).unwrap();
        let root = read_json(&path);
        let e = &root[KEY]["rtok"];
        assert_eq!(e["args"][0], "mcp");
        assert!(e.get("type").is_none(), "{e}");
        assert_eq!(root[KEY]["other"]["command"], "x");
        assert_eq!(root["amp.tools.disable"][0], "a");
        assert!(root.get("amp").is_none());
        assert!(fs::read_to_string(&path).unwrap().contains("// keep me"));
        assert_eq!(Amp.installed(&c, Kind::Cli), ["mcp"]);
        assert_eq!(register_mcp(&c).unwrap(), NO_CHANGES);

        unregister_mcp(&c).unwrap();
        let root = read_json(&path);
        assert!(root[KEY].get("rtok").is_none());
        assert_eq!(root[KEY]["other"]["command"], "x");
        assert!(Amp.installed(&c, Kind::Cli).is_empty());
        let _ = fs::remove_dir_all(path.parent().unwrap());
    }

    #[test]
    fn a_lone_settings_jsonc_is_edited_in_place() {
        let (c, path) = cfg("jsonc", false);
        let jsonc_path = path.with_extension("jsonc");
        fs::write(&jsonc_path, "{ // mine\n}\n").unwrap();
        assert_eq!(settings_path(&c), jsonc_path);
        register_mcp(&c).unwrap();
        assert!(!path.exists());
        assert!(fs::read_to_string(&jsonc_path).unwrap().contains("// mine"));
        assert_eq!(Amp.installed(&c, Kind::Cli), ["mcp"]);

        // Both present: the .json file wins, as the docs list it first.
        fs::write(&path, "{}\n").unwrap();
        assert_eq!(settings_path(&c), path);
        let _ = fs::remove_dir_all(path.parent().unwrap());
    }

    #[test]
    fn apply_is_idempotent_via_the_agent_trait() {
        let (c, path) = cfg("apply", false);
        super::super::assert_mcp_only_apply_idempotent(&Amp, &c, Kind::Cli, "amp.mcpServers");
        let _ = fs::remove_dir_all(path.parent().unwrap());
    }
}
