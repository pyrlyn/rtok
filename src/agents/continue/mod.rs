// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! Continue installer (`rtok agents install continue`, plan T413.7).
//!
//! The VS Code and JetBrains extensions and the `cn` CLI read one user file,
//! `~/.continue/config.yaml`, and its `mcpServers` is a list of `{name, type, command, args}`
//! items. The file is YAML that the user annotates, so the entry is edited as text (see
//! [`super::yaml_text`]): only the item named `rtok` is added, replaced or dropped, and every
//! other byte stays. `serde-saphyr` (already a dependency) reads the file to refuse an
//! unreadable document and to judge the item; it never writes.
//!
//! Not written: `.continue/mcpServers/` (documented for a workspace only, and the file there is
//! a block with its own `name`/`version`/`schema`), hooks and a plugin directory (Continue
//! documents neither), and a proxy URL (model endpoints live per model in `config.yaml`).

use std::ops::Range;
use std::path::{Path, PathBuf};

use anyhow::{Context, Result, bail};
use rtok_agent_sdk::{NO_CHANGES, judge_owned, runs_bin};
use serde_json::{Map, Value, json};

use super::yaml_text::{eol_of, indent_of, read_or_empty, scalar, skippable, splice, text};
use super::{
    Agent, Kind, Mode, Support, Variant, apply, apply_mcp_only, is_rtok_bin, mcp_args, mcp_summary,
    rtok_command, support_mcp_only,
};
use crate::config::Config;

/// Continue: the IDE extensions and the `cn` CLI share one `config.yaml`.
pub struct Continue;

const HOST: &str = "continue";

static VARIANTS: [Variant; 2] = [
    Variant {
        kind: Kind::Cli,
        name: "Continue CLI",
        bins: &["cn"],
        apps: &[],
    },
    Variant {
        kind: Kind::Desktop,
        name: "Continue",
        bins: &[],
        apps: &[
            "/Applications/Visual Studio Code.app",
            "$LOCALAPPDATA/Programs/Microsoft VS Code/Code.exe",
        ],
    },
];

/// Continue's user `config.yaml`.
pub fn config_path(cfg: &Config) -> PathBuf {
    cfg.setup.r#continue.config_path.clone()
}

/// The keys of an item that decide whether it is rtok's; the others (`env`, `cwd`,
/// `connectionTimeout`, ...) are the user's to add.
const CORE: [&str; 4] = ["name", "type", "command", "args"];

fn entry(cmd: &str) -> Value {
    json!({"name": "rtok", "type": "stdio", "command": cmd, "args": mcp_args(HOST)})
}

fn core(v: &Value) -> Value {
    let kept: Map<String, Value> = v
        .as_object()
        .into_iter()
        .flatten()
        .filter(|(k, _)| CORE.contains(&k.as_str()))
        .map(|(k, v)| (k.clone(), v.clone()))
        .collect();
    Value::Object(kept)
}

/// The `- name: rtok` item, `indent` columns in, one line per `eol`.
fn render(indent: usize, cmd: &str, eol: &str) -> String {
    let (p, q) = (" ".repeat(indent), " ".repeat(indent + 2));
    let args: Vec<String> = mcp_args(HOST).iter().map(|a| scalar(a)).collect();
    [
        format!("{p}- name: rtok"),
        format!("{q}type: stdio"),
        format!("{q}command: {}", scalar(cmd)),
        format!("{q}args: [{}]", args.join(", ")),
    ]
    .iter()
    .map(|l| format!("{l}{eol}"))
    .collect()
}

/// A file with nothing in it. `name`, `version` and `schema` are the three properties the
/// config reference marks required, so a file rtok creates is one Continue accepts.
fn fresh(cmd: &str, eol: &str) -> String {
    format!(
        "name: Local Config{eol}version: 1.0.0{eol}schema: v1{eol}mcpServers:{eol}{}",
        render(2, cmd, eol)
    )
}

/// Where `mcpServers:` and its `rtok` item sit, as line numbers.
struct Found {
    header: usize,
    /// `mcpServers: []`: the header line itself is replaced.
    empty_flow: bool,
    /// One past the last content line of the `mcpServers` block.
    end: usize,
    item_indent: usize,
    items: Vec<Range<usize>>,
    rtok: Option<Range<usize>>,
}

/// Locate the block-style `mcpServers:` sequence by its lines. Any other flow value on the key
/// line is an error, not a guess.
fn locate(lines: &[&str]) -> Result<Option<Found>> {
    let Some(header) = lines
        .iter()
        .position(|l| text(l).starts_with("mcpServers:"))
    else {
        return Ok(None);
    };
    let tail = text(lines[header])["mcpServers:".len()..].trim();
    let empty_flow = tail == "[]";
    if !empty_flow && !tail.is_empty() && !tail.starts_with('#') {
        bail!("`mcpServers:` is not a block list (`{tail}`); add the rtok item by hand");
    }
    let mut end = header + 1;
    for (i, l) in lines.iter().enumerate().skip(header + 1) {
        if skippable(l) {
            continue;
        }
        // A list may sit at the key's own indent (`mcpServers:` / `- name: x`).
        if indent_of(l) == 0 && !text(l).starts_with('-') {
            break;
        }
        end = i + 1;
    }
    let item_indent = lines[header + 1..end]
        .iter()
        .find(|l| !skippable(l))
        .map_or(2, |l| indent_of(l));
    let starts: Vec<usize> = (header + 1..end)
        .filter(|&i| {
            let t = text(lines[i]);
            indent_of(lines[i]) == item_indent
                && (t[item_indent..] == *"-" || t[item_indent..].starts_with("- "))
        })
        .collect();
    let items: Vec<Range<usize>> = starts
        .iter()
        .enumerate()
        .map(|(n, &s)| {
            let stop = starts.get(n + 1).copied().unwrap_or(end);
            let last = (s..stop).rev().find(|&j| !skippable(lines[j])).unwrap_or(s);
            s..last + 1
        })
        .collect();
    let rtok = items
        .iter()
        .find(|r| parse_item(&lines[(*r).clone()]).is_some_and(|v| v["name"] == "rtok"))
        .cloned();
    Ok(Some(Found {
        header,
        empty_flow,
        end,
        item_indent,
        items,
        rtok,
    }))
}

/// One list item as a value; `None` when it is not a mapping Continue could read.
fn parse_item(lines: &[&str]) -> Option<Value> {
    let list: Value = serde_saphyr::from_str(&lines.concat()).ok()?;
    list.get(0).filter(|v| v.is_object()).cloned()
}

/// A whole document is readable and `mcpServers` is a list, before any byte is changed.
fn validate(raw: &str, path: &Path) -> Result<()> {
    if raw.trim().is_empty() {
        return Ok(());
    }
    let root: Value = serde_saphyr::from_str(raw)
        .with_context(|| format!("{} is not valid YAML; fix it first", path.display()))?;
    match root.get("mcpServers") {
        None | Some(Value::Null | Value::Array(_)) => Ok(()),
        Some(_) => bail!("{}: `mcpServers` is not a list", path.display()),
    }
}

/// The `rtok` item of `mcpServers` in Continue's `config.yaml`.
pub fn register_mcp(cfg: &Config) -> Result<String> {
    let path = config_path(cfg);
    let raw = read_or_empty(&path)?;
    validate(&raw, &path)?;
    let (cmd, eol) = (rtok_command(), eol_of(&raw));
    let lines: Vec<&str> = raw.split_inclusive('\n').collect();
    let body = if raw.trim().is_empty() {
        fresh(&cmd, eol)
    } else {
        match locate(&lines)? {
            Some(Found {
                rtok: Some(range),
                item_indent,
                ..
            }) => {
                let have = parse_item(&lines[range.clone()]).unwrap_or(Value::Null);
                if core(&have) == core(&entry(&cmd)) {
                    return Ok(NO_CHANGES.into());
                }
                splice(&lines, range, &render(item_indent, &cmd, eol), eol)
            }
            Some(f) if f.empty_flow => splice(
                &lines,
                f.header..f.header + 1,
                &format!("mcpServers:{eol}{}", render(2, &cmd, eol)),
                eol,
            ),
            Some(f) => splice(&lines, f.end..f.end, &render(f.item_indent, &cmd, eol), eol),
            None => {
                let block = format!("mcpServers:{eol}{}", render(2, &cmd, eol));
                splice(&lines, lines.len()..lines.len(), &block, eol)
            }
        }
    };
    let report = format!("mcpServers.rtok: {}", mcp_summary(&cmd, HOST));
    rtok_agent_sdk::write(&apply(cfg), &path, &body, &report)?;
    Ok(report)
}

/// Drop the `rtok` item (`rtok agents remove continue`) as far as rtok wrote it.
pub fn unregister_mcp(cfg: &Config) -> Result<String> {
    let path = config_path(cfg);
    let raw = read_or_empty(&path)?;
    validate(&raw, &path)?;
    let lines: Vec<&str> = raw.split_inclusive('\n').collect();
    let Some(Found {
        header,
        end,
        items,
        rtok: Some(range),
        ..
    }) = locate(&lines)?
    else {
        return Ok(NO_CHANGES.into());
    };
    let have = parse_item(&lines[range.clone()]).unwrap_or(Value::Null);
    let at = format!("mcpServers.rtok in {}", path.display());
    if let Some(leave) = judge_owned(&apply(cfg), &at, &core(&have), &entry("rtok"), is_rtok_bin) {
        return Ok(leave);
    }
    // The header goes with its last item, so the file reads as before rtok arrived.
    let drop = if items.len() == 1 { header..end } else { range };
    let body = splice(&lines, drop, "", eol_of(&raw));
    let report = "- mcpServers.rtok".to_string();
    rtok_agent_sdk::write(&apply(cfg), &path, &body, &report)?;
    Ok(report)
}

impl Agent for Continue {
    super::agent_identity!(HOST);

    fn support(&self, _kind: Kind, module: &str) -> Support {
        support_mcp_only(
            module,
            "Continue's docs describe config.yaml, mcpServers and rules, not shell hook events",
            "Continue sets a model's apiBase per model in config.yaml; there is no single endpoint file to point at the proxy",
            "Continue has no documented local plugin directory to link; blocks and rules live in config.yaml and .continue/",
        )
    }

    fn files(&self, cfg: &Config, _kind: Kind) -> Vec<PathBuf> {
        vec![config_path(cfg)]
    }

    fn installed(&self, cfg: &Config, _kind: Kind) -> Vec<&'static str> {
        let raw = super::read(&config_path(cfg));
        let lines: Vec<&str> = raw.split_inclusive('\n').collect();
        let ours = locate(&lines)
            .ok()
            .flatten()
            .and_then(|f| f.rtok)
            .and_then(|r| parse_item(&lines[r]))
            .is_some_and(|v| runs_bin(&v, is_rtok_bin));
        if ours { vec!["mcp"] } else { Vec::new() }
    }

    fn apply(&self, cfg: &Config, _kind: Kind, mode: Mode) -> Result<Vec<String>> {
        apply_mcp_only(cfg, mode, register_mcp, unregister_mcp)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;

    fn cfg(name: &str, dry: bool) -> (Config, PathBuf) {
        super::super::test_scratch_cfg("continue", name, "config.yaml", dry, |c, path| {
            c.setup.r#continue.config_path = path;
        })
    }

    fn install(c: &Config) -> String {
        register_mcp(c).unwrap()
    }

    fn remove(c: &Config) -> String {
        unregister_mcp(c).unwrap()
    }

    const FOREIGN: &str = "# my continue\nname: Mine\nversion: 1.0.0\nschema: v1\nmodels:\n  - name: m\n    provider: ollama\n    model: llama\nmcpServers:\n  # tools\n  - name: other\n    command: npx\n    args:\n      - x\n\nrules:\n  - be brief\n";

    #[test]
    fn a_missing_file_gets_a_valid_config_and_removal_deletes_the_block_again() {
        let (c, path) = cfg("fresh", false);
        assert!(install(&c).starts_with("mcpServers.rtok: "));
        let body = fs::read_to_string(&path).unwrap();
        assert!(
            body.starts_with(
                "name: Local Config\nversion: 1.0.0\nschema: v1\nmcpServers:\n  - name: rtok\n"
            ),
            "{body}"
        );
        assert!(body.contains("args: [mcp, --host, continue]"), "{body}");
        assert!(serde_saphyr::from_str::<Value>(&body).is_ok());
        assert_eq!(Continue.installed(&c, Kind::Cli), ["mcp"]);
        assert_eq!(install(&c), NO_CHANGES);
        assert_eq!(remove(&c), "- mcpServers.rtok");
        assert_eq!(
            fs::read_to_string(&path).unwrap(),
            "name: Local Config\nversion: 1.0.0\nschema: v1\n"
        );
        assert_eq!(remove(&c), NO_CHANGES);
        assert!(Continue.installed(&c, Kind::Cli).is_empty());
    }

    #[test]
    fn install_appends_after_the_last_item_and_keeps_every_other_byte() {
        let (c, path) = cfg("foreign", false);
        fs::write(&path, FOREIGN).unwrap();
        install(&c);
        let body = fs::read_to_string(&path).unwrap();
        let want = FOREIGN.replace(
            "      - x\n\nrules",
            &format!("      - x\n{}\nrules", render(2, &rtok_command(), "\n")),
        );
        assert_eq!(body, want);
        assert_eq!(remove(&c), "- mcpServers.rtok");
        assert_eq!(fs::read_to_string(&path).unwrap(), FOREIGN);
    }

    #[test]
    fn a_list_at_the_keys_own_indent_is_extended_in_place() {
        let (c, path) = cfg("flush", false);
        let src = "name: n\nmcpServers:\n- name: other\n  command: x\nrules: []\n";
        fs::write(&path, src).unwrap();
        install(&c);
        let body = fs::read_to_string(&path).unwrap();
        assert!(
            body.starts_with(
                "name: n\nmcpServers:\n- name: other\n  command: x\n- name: rtok\n  type: stdio\n"
            ),
            "{body}"
        );
        assert!(body.ends_with("rules: []\n"), "{body}");
        remove(&c);
        assert_eq!(fs::read_to_string(&path).unwrap(), src);
    }

    #[test]
    fn an_empty_list_value_or_header_takes_the_item() {
        for (name, src) in [
            ("emptyflow", "mcpServers: []\nname: n\n"),
            ("emptybare", "mcpServers:\nname: n\n"),
        ] {
            let (c, path) = cfg(name, false);
            fs::write(&path, src).unwrap();
            install(&c);
            let body = fs::read_to_string(&path).unwrap();
            assert!(body.starts_with("mcpServers:\n  - name: rtok\n"), "{body}");
            assert!(body.ends_with("name: n\n"), "{body}");
            assert!(serde_saphyr::from_str::<Value>(&body).is_ok());
            remove(&c);
            assert_eq!(fs::read_to_string(&path).unwrap(), "name: n\n");
        }
    }

    #[test]
    fn extra_keys_on_the_item_are_not_an_edit() {
        let (c, path) = cfg("extra", false);
        install(&c);
        let saved = fs::read_to_string(&path).unwrap() + "    env:\n      A: b\n    cwd: /tmp\n";
        fs::write(&path, &saved).unwrap();
        assert_eq!(install(&c), NO_CHANGES);
        assert_eq!(remove(&c), "- mcpServers.rtok");
    }

    #[test]
    fn a_changed_item_is_rewritten_on_install_and_kept_on_a_declined_remove() {
        let (c, path) = cfg("edited", false);
        install(&c);
        let edited = fs::read_to_string(&path)
            .unwrap()
            .replace("type: stdio", "type: sse");
        fs::write(&path, &edited).unwrap();
        let out = remove(&c);
        assert!(out.starts_with("leave mcpServers.rtok"), "{out}");
        assert_eq!(fs::read_to_string(&path).unwrap(), edited);
        assert!(install(&c).starts_with("mcpServers.rtok: "));
        assert!(fs::read_to_string(&path).unwrap().contains("type: stdio"));
    }

    #[test]
    fn another_command_under_the_rtok_name_is_never_removed() {
        let (c, path) = cfg("notours", false);
        let body =
            "mcpServers:\n  - name: rtok\n    type: stdio\n    command: other\n    args: [x]\n";
        fs::write(&path, body).unwrap();
        let out = remove(&c);
        assert!(out.starts_with("leave mcpServers.rtok"), "{out}");
        assert_eq!(fs::read_to_string(&path).unwrap(), body);
    }

    #[test]
    fn a_dry_run_reports_and_writes_nothing() {
        let (c, path) = cfg("dry", true);
        assert!(install(&c).starts_with("mcpServers.rtok: "));
        assert!(!path.exists());
    }

    #[test]
    fn crlf_files_keep_their_line_endings() {
        let (c, path) = cfg("crlf", false);
        let src = "name: n\r\nmcpServers:\r\n  - name: o\r\n    command: x\r\n";
        fs::write(&path, src).unwrap();
        install(&c);
        let body = fs::read_to_string(&path).unwrap();
        assert_eq!(
            body.matches('\n').count(),
            body.matches("\r\n").count(),
            "{body:?}"
        );
        remove(&c);
        assert_eq!(fs::read_to_string(&path).unwrap(), src);
    }

    #[test]
    fn an_unreadable_document_or_a_wrong_mcp_servers_value_is_an_error_and_untouched() {
        for (name, body) in [
            ("junk", "mcpServers: [\n"),
            ("map", "mcpServers:\n  a: b\n"),
            ("flow", "mcpServers: [{name: o}]\n"),
        ] {
            let (c, path) = cfg(name, false);
            fs::write(&path, body).unwrap();
            assert!(register_mcp(&c).is_err(), "{name}");
            assert_eq!(fs::read_to_string(&path).unwrap(), body);
        }
    }

    #[test]
    fn a_file_without_the_key_gets_the_block_at_the_end() {
        let (c, path) = cfg("nokey", false);
        fs::write(&path, "name: n").unwrap();
        install(&c);
        let body = fs::read_to_string(&path).unwrap();
        assert!(
            body.starts_with("name: n\nmcpServers:\n  - name: rtok\n"),
            "{body}"
        );
        assert!(serde_saphyr::from_str::<Value>(&body).is_ok());
    }
}
