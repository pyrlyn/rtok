// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! Reasonix installer (`rtok agents install reasonix`, plan T413.15).
//!
//! Reasonix keeps its global configuration in `<Reasonix home>/config.toml`, and each MCP server
//! is one `[[plugins]]` table (`name`, `command`, `args`, `env`; `type` defaults to `stdio`).
//! That array is keyed by `name`, not by a table name like Codex's `[mcp_servers.<name>]`, so
//! this host edits it through `toml_edit` itself: the user's comments, providers and other
//! servers survive byte for byte and only the `name = "rtok"` table is ever added or removed.
//!
//! `[setup.reasonix] dir` is the Reasonix home (`~/.reasonix`; Windows keeps it in
//! `%APPDATA%\reasonix` and `REASONIX_HOME` moves it). Project `reasonix.toml` and `.mcp.json`
//! files belong to the project and are never written. Hooks live in `settings.json` with their
//! own payload and tool names and stay out until rtok has a checked mapping; `research.md`
//! section 32.15 has the sources.

use std::path::PathBuf;

use anyhow::{Result, bail};
use rtok_agent_sdk::NO_CHANGES;
use serde_json::{Value, json};
use toml_edit::{ArrayOfTables, DocumentMut, Item, Table, value};

use super::{Agent, Kind, Mode, Support, Variant, apply, load_toml, rtok_mcp_command};
use crate::config::Config;

/// Reasonix: the `reasonix` CLI (`dsnix` is its shim). The desktop app shares the same config
/// file, but no install path of it is documented, so there is no Desktop variant.
pub struct Reasonix;

const HOST: &str = "reasonix";
const NAME: &str = "rtok";

static VARIANTS: [Variant; 1] = [Variant {
    kind: Kind::Cli,
    name: "Reasonix",
    bins: &["reasonix", "dsnix"],
    apps: &[],
}];

/// `<Reasonix home>/config.toml`, the global config the CLI and the desktop app share.
pub fn config_path(cfg: &Config) -> PathBuf {
    cfg.setup.reasonix.dir.join("config.toml")
}

fn is_rtok_table(t: &Table) -> bool {
    t.get("name").and_then(Item::as_str) == Some(NAME)
}

/// The `[[plugins]]` array, if the document has one that is an array of tables. A `plugins`
/// that is anything else is the user's and an error, never overwritten.
fn plugins(doc: &DocumentMut) -> Result<Option<&ArrayOfTables>> {
    match doc.get("plugins") {
        None => Ok(None),
        Some(Item::ArrayOfTables(a)) => Ok(Some(a)),
        Some(_) => bail!("`plugins` is not an array of tables"),
    }
}

/// The entry [`register_mcp`] writes, as the JSON [`rtok_agent_sdk::judge_owned`] compares a
/// live table against. `cmd` only stands in for the comparison: every rtok binary string folds
/// to one placeholder there.
fn mcp_entry(cmd: &str) -> Value {
    json!({"name": NAME, "command": cmd, "args": super::mcp_args(HOST)})
}

/// `[[plugins]] name = "rtok"` in `config.toml`. An `rtok` table that is already there stays,
/// whoever wrote it.
pub fn register_mcp(cfg: &Config) -> Result<String> {
    let path = config_path(cfg);
    let mut doc = load_toml(&path)?;
    if plugins(&doc)?.is_some_and(|a| a.iter().any(is_rtok_table)) {
        return Ok(NO_CHANGES.into());
    }
    let cmd = rtok_mcp_command();
    let mut entry = Table::new();
    entry.insert("name", value(NAME));
    entry.insert("command", value(&cmd));
    entry.insert(
        "args",
        value(toml_edit::Array::from_iter(super::mcp_args(HOST))),
    );
    match doc
        .entry("plugins")
        .or_insert_with(|| Item::ArrayOfTables(ArrayOfTables::new()))
    {
        Item::ArrayOfTables(a) => a.push(entry),
        _ => bail!("`plugins` is not an array of tables"),
    }
    let report = format!("plugins.rtok: {}", super::mcp_summary(&cmd, HOST));
    rtok_agent_sdk::write(&apply(cfg), &path, &doc.to_string(), &report)?;
    Ok(report)
}

/// Drop the `rtok` table, unless the user edited it (T246): a table that does not run the rtok
/// binary, or that differs from [`mcp_entry`], stays unless `--yes` says remove. The `plugins`
/// key goes with its last table, so a removed entry leaves the file as it was before rtok.
pub fn unregister_mcp(cfg: &Config) -> Result<String> {
    let path = config_path(cfg);
    let mut doc = load_toml(&path)?;
    let Some(at) = plugins(&doc)?.and_then(|a| a.iter().position(is_rtok_table)) else {
        return Ok(NO_CHANGES.into());
    };
    let have = super::mcp::toml_item_to_json(&Item::Table(
        plugins(&doc)?.and_then(|a| a.get(at)).cloned().unwrap(),
    ));
    let label = format!("plugins.rtok in {}", path.display());
    if let Some(leave) = rtok_agent_sdk::judge_owned(
        &apply(cfg),
        &label,
        &have,
        &mcp_entry("rtok"),
        super::is_rtok_bin,
    ) {
        return Ok(leave);
    }
    let Some(Item::ArrayOfTables(a)) = doc.get_mut("plugins") else {
        return Ok(NO_CHANGES.into());
    };
    a.remove(at);
    if a.is_empty() {
        doc.remove("plugins");
    }
    rtok_agent_sdk::write(&apply(cfg), &path, &doc.to_string(), "- plugins.rtok")?;
    Ok("- plugins.rtok".into())
}

impl Agent for Reasonix {
    super::agent_identity!(HOST);

    fn support(&self, _kind: Kind, module: &str) -> Support {
        super::support_mcp_only(
            module,
            "Reasonix hooks (settings.json) use their own event payload and tool names, which rtok has no checked mapping for",
            "Reasonix has no single endpoint setting; each [[providers]] entry carries its own base_url",
            "Reasonix plugin packages are its own format; rtok ships no Reasonix plugin",
        )
    }

    fn files(&self, cfg: &Config, _kind: Kind) -> Vec<PathBuf> {
        vec![config_path(cfg)]
    }

    fn installed(&self, cfg: &Config, _kind: Kind) -> Vec<&'static str> {
        let has = load_toml(&config_path(cfg)).ok().is_some_and(|d| {
            plugins(&d)
                .ok()
                .flatten()
                .is_some_and(|a| a.iter().any(is_rtok_table))
        });
        if has { vec!["mcp"] } else { Vec::new() }
    }

    fn apply(&self, cfg: &Config, _kind: Kind, mode: Mode) -> Result<Vec<String>> {
        super::apply_mcp_only(cfg, mode, register_mcp, unregister_mcp)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;

    fn cfg(name: &str, dry: bool) -> (Config, PathBuf) {
        super::super::test_scratch_cfg("reasonix", name, "config.toml", dry, |c, path| {
            c.setup.reasonix.dir = path.parent().unwrap().to_path_buf();
        })
    }

    const FOREIGN: &str = "# my settings\ndefault_model = \"deepseek/deepseek-flash\"\n\n[[providers]]\nname = \"deepseek\"\nbase_url = \"https://api.deepseek.com/anthropic\"\n\n[[plugins]]\nname = \"foreign\"   # keep me\ncommand = \"foreign-mcp\"\n";

    #[test]
    fn the_default_dir_is_the_reasonix_home() {
        let c = Config::default();
        assert!(
            config_path(&c).ends_with(".reasonix/config.toml"),
            "{:?}",
            config_path(&c)
        );
    }

    #[test]
    fn dry_run_names_the_table_and_creates_nothing() {
        let (c, path) = cfg("dry", true);
        let out = register_mcp(&c).unwrap();
        assert!(out.starts_with("plugins.rtok: "), "{out}");
        assert!(out.contains("mcp --host reasonix"), "{out}");
        assert!(!path.exists());
        assert!(Reasonix.installed(&c, Kind::Cli).is_empty());
        let _ = fs::remove_dir_all(path.parent().unwrap());
    }

    #[test]
    fn install_adds_one_plugins_table_and_keeps_every_foreign_byte() {
        let (c, path) = cfg("keep", false);
        fs::write(&path, FOREIGN).unwrap();
        register_mcp(&c).unwrap();
        let raw = fs::read_to_string(&path).unwrap();
        assert!(raw.starts_with(FOREIGN), "{raw}");
        let doc: DocumentMut = raw.parse().unwrap();
        let added: Vec<_> = doc["plugins"]
            .as_array_of_tables()
            .unwrap()
            .iter()
            .filter(|t| is_rtok_table(t))
            .collect();
        assert_eq!(added.len(), 1, "{raw}");
        assert_eq!(added[0]["command"].as_str().unwrap(), rtok_mcp_command());
        let args: Vec<_> = added[0]["args"]
            .as_array()
            .unwrap()
            .iter()
            .filter_map(|v| v.as_str())
            .collect();
        assert_eq!(args, ["mcp", "--host", "reasonix"]);
        assert_eq!(Reasonix.installed(&c, Kind::Cli), ["mcp"]);
        assert_eq!(register_mcp(&c).unwrap(), NO_CHANGES);
        assert_eq!(
            fs::read_to_string(&path).unwrap(),
            raw,
            "second install is a no-op"
        );
        let _ = fs::remove_dir_all(path.parent().unwrap());
    }

    #[test]
    fn remove_restores_the_file_and_drops_an_emptied_plugins_key() {
        let (c, path) = cfg("remove", false);
        fs::write(&path, FOREIGN).unwrap();
        register_mcp(&c).unwrap();
        assert_eq!(unregister_mcp(&c).unwrap(), "- plugins.rtok");
        assert_eq!(fs::read_to_string(&path).unwrap(), FOREIGN);
        assert_eq!(unregister_mcp(&c).unwrap(), NO_CHANGES);

        let only = "default_model = \"x\"\n";
        fs::write(&path, only).unwrap();
        register_mcp(&c).unwrap();
        unregister_mcp(&c).unwrap();
        assert_eq!(fs::read_to_string(&path).unwrap(), only);
        let _ = fs::remove_dir_all(path.parent().unwrap());
    }

    #[test]
    fn a_user_edited_rtok_table_stays_on_remove() {
        let (mut c, path) = cfg("edited", false);
        c.setup.yes = false;
        register_mcp(&c).unwrap();
        let raw = fs::read_to_string(&path).unwrap();
        fs::write(&path, raw.replace("\"--host\"", "\"--extra\", \"--host\"")).unwrap();
        let before = fs::read_to_string(&path).unwrap();
        let out = unregister_mcp(&c).unwrap();
        assert_ne!(out, "- plugins.rtok", "{out}");
        assert_eq!(fs::read_to_string(&path).unwrap(), before);
        let _ = fs::remove_dir_all(path.parent().unwrap());
    }

    #[test]
    fn a_plugins_key_that_is_not_an_array_of_tables_is_an_error_not_overwritten() {
        let (c, path) = cfg("shape", false);
        fs::write(&path, "plugins = \"mine\"\n").unwrap();
        assert!(register_mcp(&c).is_err());
        assert_eq!(fs::read_to_string(&path).unwrap(), "plugins = \"mine\"\n");
        let _ = fs::remove_dir_all(path.parent().unwrap());
    }

    #[test]
    fn apply_is_idempotent_via_the_agent_trait() {
        let (c, path) = cfg("apply", false);
        super::super::assert_mcp_only_apply_idempotent(&Reasonix, &c, Kind::Cli, "plugins");
        let _ = fs::remove_dir_all(path.parent().unwrap());
    }
}
