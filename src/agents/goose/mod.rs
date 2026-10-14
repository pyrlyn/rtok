// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! Goose installer (`rtok agents install goose`, plan T413.6).
//!
//! Goose keeps its extensions (its MCP servers) in `config.yaml`, under `extensions.<key>`; a
//! stdio one is `{name, type: stdio, cmd, args, enabled}`. The CLI and the Desktop app read the
//! same file. The file is YAML that Goose rewrites itself and users annotate, so the entry is
//! edited as text: only the `rtok` block under `extensions:` is added, replaced or dropped, and
//! every other byte stays. `serde-saphyr` (already a dependency) reads the file to refuse an
//! unreadable document and to judge the entry; it never writes.
//!
//! Goose also runs hooks (Open Plugins `hooks/hooks.json` under `~/.agents/plugins/`), with its
//! own event names, tool names (`shell`, `edit`) and decision shape. rtok has no adapter for
//! that payload, so the hooks module stays off until one exists.

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

/// Goose: the CLI and the Desktop app share one `config.yaml`.
pub struct Goose;

const HOST: &str = "goose";

static VARIANTS: [Variant; 2] = [
    Variant {
        kind: Kind::Cli,
        name: "Goose CLI",
        bins: &["goose"],
        apps: &[],
    },
    Variant {
        kind: Kind::Desktop,
        name: "Goose",
        bins: &[],
        apps: &["/Applications/Goose.app"],
    },
];

/// Goose's `config.yaml`.
pub fn config_path(cfg: &Config) -> PathBuf {
    cfg.setup.goose.config_path.clone()
}

/// The keys of an extension that decide whether it is rtok's. Goose adds defaults of its own
/// when it re-saves the file (`envs`, `env_keys`, `timeout`, ...); those are not an edit.
const CORE: [&str; 5] = ["name", "type", "cmd", "args", "enabled"];

fn entry(cmd: &str) -> Value {
    json!({"name": "rtok", "type": "stdio", "cmd": cmd, "args": mcp_args(HOST), "enabled": true})
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

/// The `rtok:` block, `indent` columns in, one line per `eol`.
fn render(indent: usize, cmd: &str, eol: &str) -> String {
    let (p, q) = (" ".repeat(indent), " ".repeat(indent + 2));
    let args: Vec<String> = mcp_args(HOST).iter().map(|a| scalar(a)).collect();
    [
        format!("{p}rtok:"),
        format!("{q}name: rtok"),
        format!("{q}type: stdio"),
        format!("{q}cmd: {}", scalar(cmd)),
        format!("{q}args: [{}]", args.join(", ")),
        format!("{q}enabled: true"),
    ]
    .iter()
    .map(|l| format!("{l}{eol}"))
    .collect()
}

/// Where `extensions:` and its `rtok` child sit, as line numbers.
struct Found {
    header: usize,
    /// One past the last content line of the `extensions` block.
    end: usize,
    child_indent: usize,
    rtok: Option<Range<usize>>,
}

/// Locate the block-style `extensions:` mapping by its lines. A flow value on the key line
/// (`extensions: {}`) is an error, not a guess: Goose writes the block form.
fn locate(lines: &[&str]) -> Result<Option<Found>> {
    let Some(header) = lines
        .iter()
        .position(|l| text(l).starts_with("extensions:"))
    else {
        return Ok(None);
    };
    let tail = text(lines[header])["extensions:".len()..].trim();
    if !tail.is_empty() && !tail.starts_with('#') {
        bail!("`extensions:` is not a block mapping (`{tail}`); add extensions.rtok by hand");
    }
    let mut end = header + 1;
    for (i, l) in lines.iter().enumerate().skip(header + 1) {
        if skippable(l) {
            continue;
        }
        if indent_of(l) == 0 {
            break;
        }
        end = i + 1;
    }
    let child_indent = lines[header + 1..end]
        .iter()
        .find(|l| !skippable(l))
        .map_or(2, |l| indent_of(l));
    let rtok = (header + 1..end)
        .find(|&i| {
            let t = text(lines[i]);
            indent_of(lines[i]) == child_indent
                && ["rtok:", "\"rtok\":", "'rtok':"]
                    .iter()
                    .any(|k| t[child_indent..].starts_with(k))
        })
        .map(|start| {
            let last = (start + 1..end)
                .take_while(|&j| skippable(lines[j]) || indent_of(lines[j]) > child_indent)
                .filter(|&j| !skippable(lines[j]))
                .last()
                .unwrap_or(start);
            start..last + 1
        });
    Ok(Some(Found {
        header,
        end,
        child_indent,
        rtok,
    }))
}

/// A whole document is readable and `extensions` is a mapping, before any byte is changed.
fn validate(raw: &str, path: &Path) -> Result<()> {
    if raw.trim().is_empty() {
        return Ok(());
    }
    let root: Value = serde_saphyr::from_str(raw)
        .with_context(|| format!("{} is not valid YAML; fix it first", path.display()))?;
    match root.get("extensions") {
        None | Some(Value::Null | Value::Object(_)) => Ok(()),
        Some(_) => bail!("{}: `extensions` is not a mapping", path.display()),
    }
}

fn parse_block(lines: &[&str]) -> Result<Value> {
    let root: Value = serde_saphyr::from_str(&lines.concat())
        .context("extensions.rtok is not readable YAML; remove it by hand")?;
    root.get("rtok")
        .cloned()
        .context("extensions.rtok is empty")
}

/// `extensions.rtok` in Goose's `config.yaml`.
pub fn register_mcp(cfg: &Config) -> Result<String> {
    let path = config_path(cfg);
    let raw = read_or_empty(&path)?;
    validate(&raw, &path)?;
    let (cmd, eol) = (rtok_command(), eol_of(&raw));
    let lines: Vec<&str> = raw.split_inclusive('\n').collect();
    let body = match locate(&lines)? {
        Some(Found {
            rtok: Some(range), ..
        }) => {
            if core(&parse_block(&lines[range.clone()])?) == core(&entry(&cmd)) {
                return Ok(NO_CHANGES.into());
            }
            let indent = indent_of(lines[range.start]);
            splice(&lines, range, &render(indent, &cmd, eol), eol)
        }
        Some(f) => splice(
            &lines,
            f.end..f.end,
            &render(f.child_indent, &cmd, eol),
            eol,
        ),
        None => {
            let block = format!("extensions:{eol}{}", render(2, &cmd, eol));
            splice(&lines, lines.len()..lines.len(), &block, eol)
        }
    };
    let report = format!("extensions.rtok: {}", mcp_summary(&cmd, HOST));
    rtok_agent_sdk::write(&apply(cfg), &path, &body, &report)?;
    Ok(report)
}

/// Drop `extensions.rtok` (`rtok agents remove goose`) as far as rtok wrote it.
pub fn unregister_mcp(cfg: &Config) -> Result<String> {
    let path = config_path(cfg);
    let raw = read_or_empty(&path)?;
    validate(&raw, &path)?;
    let lines: Vec<&str> = raw.split_inclusive('\n').collect();
    let Some(Found {
        header,
        end,
        rtok: Some(range),
        ..
    }) = locate(&lines)?
    else {
        return Ok(NO_CHANGES.into());
    };
    let have = parse_block(&lines[range.clone()])?;
    let at = format!("extensions.rtok in {}", path.display());
    if let Some(leave) = judge_owned(&apply(cfg), &at, &core(&have), &entry("rtok"), is_rtok_bin) {
        return Ok(leave);
    }
    // The header goes with its last child, so the file reads as before rtok arrived.
    let alone = (header + 1..end)
        .filter(|i| !range.contains(i))
        .all(|i| text(lines[i]).trim().is_empty());
    let drop = if alone { header..end } else { range };
    let body = splice(&lines, drop, "", eol_of(&raw));
    let report = "- extensions.rtok".to_string();
    rtok_agent_sdk::write(&apply(cfg), &path, &body, &report)?;
    Ok(report)
}

impl Agent for Goose {
    super::agent_identity!(HOST);

    fn support(&self, _kind: Kind, module: &str) -> Support {
        support_mcp_only(
            module,
            "Goose runs hooks from Open Plugins directories with its own event names, tool names and decision shape; rtok has no adapter for that payload yet",
            "Goose documents its provider endpoint as the GOOSE_PROVIDER__HOST environment variable only; there is no file to point at the proxy",
            "Goose plugins are Open Plugins directories (plugin.json, hooks/hooks.json); rtok ships no bundle in that format yet",
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
            .and_then(|r| parse_block(&lines[r]).ok())
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
        super::super::test_scratch_cfg("goose", name, "config.yaml", dry, |c, path| {
            c.setup.goose.config_path = path;
        })
    }

    fn install(c: &Config) -> String {
        Goose.apply(c, Kind::Cli, Mode::Install).unwrap().remove(0)
    }

    fn remove(c: &Config) -> String {
        Goose.apply(c, Kind::Cli, Mode::Remove).unwrap().remove(0)
    }

    const FOREIGN: &str = "# my goose\nGOOSE_PROVIDER: openai\nextensions:\n  # tools\n  developer:\n    type: builtin\n    name: developer\n    enabled: true\n\nGOOSE_MODE: auto\n";

    #[test]
    fn a_missing_file_gets_only_rtoks_block_and_removal_deletes_it_again() {
        let (c, path) = cfg("fresh", false);
        assert!(install(&c).starts_with("extensions.rtok: "));
        let body = fs::read_to_string(&path).unwrap();
        assert!(
            body.starts_with("extensions:\n  rtok:\n    name: rtok\n"),
            "{body}"
        );
        assert!(body.contains("args: [mcp, --host, goose]"), "{body}");
        assert_eq!(Goose.installed(&c, Kind::Cli), ["mcp"]);
        assert_eq!(install(&c), NO_CHANGES);
        assert_eq!(remove(&c), "- extensions.rtok");
        assert_eq!(fs::read_to_string(&path).unwrap(), "");
        assert_eq!(remove(&c), NO_CHANGES);
        assert!(Goose.installed(&c, Kind::Cli).is_empty());
    }

    #[test]
    fn install_adds_after_the_last_extension_and_keeps_every_other_byte() {
        let (c, path) = cfg("foreign", false);
        fs::write(&path, FOREIGN).unwrap();
        install(&c);
        let body = fs::read_to_string(&path).unwrap();
        let want = FOREIGN.replace(
            "    enabled: true\n\nGOOSE_MODE",
            &format!(
                "    enabled: true\n{}\nGOOSE_MODE",
                render(2, &rtok_command(), "\n")
            ),
        );
        assert_eq!(body, want);
        assert_eq!(remove(&c), "- extensions.rtok");
        assert_eq!(fs::read_to_string(&path).unwrap(), FOREIGN);
    }

    #[test]
    fn an_empty_extensions_header_takes_the_block_and_goes_with_it() {
        let (c, path) = cfg("header", false);
        fs::write(&path, "extensions:\nGOOSE_MODE: auto\n").unwrap();
        install(&c);
        assert!(
            fs::read_to_string(&path)
                .unwrap()
                .contains("extensions:\n  rtok:\n")
        );
        remove(&c);
        // An empty header is not worth a second state: it leaves with the entry it held.
        assert_eq!(fs::read_to_string(&path).unwrap(), "GOOSE_MODE: auto\n");
    }

    #[test]
    fn goose_resaving_defaults_into_the_entry_is_not_an_edit() {
        let (c, path) = cfg("resave", false);
        install(&c);
        let saved = fs::read_to_string(&path).unwrap()
            + "    envs: {}\n    env_keys: []\n    timeout: 300\n";
        fs::write(&path, &saved).unwrap();
        assert_eq!(install(&c), NO_CHANGES);
        assert_eq!(remove(&c), "- extensions.rtok");
        assert_eq!(fs::read_to_string(&path).unwrap(), "");
    }

    #[test]
    fn a_changed_entry_is_rewritten_on_install_and_kept_on_a_declined_remove() {
        let (c, path) = cfg("edited", false);
        install(&c);
        let edited = fs::read_to_string(&path)
            .unwrap()
            .replace("enabled: true", "enabled: false");
        fs::write(&path, &edited).unwrap();
        let out = remove(&c);
        assert!(out.starts_with("leave extensions.rtok"), "{out}");
        assert_eq!(fs::read_to_string(&path).unwrap(), edited);
        assert!(install(&c).starts_with("extensions.rtok: "));
        assert!(fs::read_to_string(&path).unwrap().contains("enabled: true"));
    }

    #[test]
    fn a_foreign_rtok_key_is_never_removed() {
        let (c, path) = cfg("notours", false);
        let body = "extensions:\n  rtok:\n    type: stdio\n    cmd: other\n    args: [x]\n";
        fs::write(&path, body).unwrap();
        let out = remove(&c);
        assert!(out.contains("not rtok's"), "{out}");
        assert_eq!(fs::read_to_string(&path).unwrap(), body);
    }

    #[test]
    fn a_dry_run_reports_and_writes_nothing() {
        let (c, path) = cfg("dry", true);
        assert!(install(&c).starts_with("extensions.rtok: "));
        assert!(!path.exists());
    }

    #[test]
    fn crlf_files_keep_their_line_endings() {
        let (c, path) = cfg("crlf", false);
        fs::write(
            &path,
            "GOOSE_MODE: auto\r\nextensions:\r\n  dev:\r\n    type: builtin\r\n",
        )
        .unwrap();
        install(&c);
        let body = fs::read_to_string(&path).unwrap();
        assert_eq!(
            body.matches('\n').count(),
            body.matches("\r\n").count(),
            "{body:?}"
        );
        remove(&c);
        assert_eq!(
            fs::read_to_string(&path).unwrap(),
            "GOOSE_MODE: auto\r\nextensions:\r\n  dev:\r\n    type: builtin\r\n"
        );
    }

    #[test]
    fn an_unreadable_document_or_a_flow_extensions_value_is_an_error_and_untouched() {
        for (name, body) in [
            ("broken", "extensions: [\n  oops"),
            ("flow", "extensions: {}\n"),
            ("scalar", "extensions: 3\n"),
        ] {
            let (c, path) = cfg(name, false);
            fs::write(&path, body).unwrap();
            assert!(Goose.apply(&c, Kind::Cli, Mode::Install).is_err(), "{name}");
            assert_eq!(fs::read_to_string(&path).unwrap(), body);
        }
    }

    #[test]
    fn a_path_with_spaces_is_quoted_so_the_yaml_reads_back() {
        assert_eq!(scalar("rtok"), "rtok");
        assert_eq!(scalar("--host"), "--host");
        assert_eq!(scalar("/My Apps/rtok"), "\"/My Apps/rtok\"");
        let block = render(2, r"C:\Users\me\rtok.exe", "\n");
        let v: Value = serde_saphyr::from_str(&block).unwrap();
        assert_eq!(v["rtok"]["cmd"], r"C:\Users\me\rtok.exe");
        assert_eq!(v["rtok"]["args"][2], "goose");
    }

    #[test]
    fn a_file_without_a_final_newline_still_gets_a_clean_block() {
        let (c, path) = cfg("nonl", false);
        fs::write(&path, "GOOSE_MODE: auto").unwrap();
        install(&c);
        let body = fs::read_to_string(&path).unwrap();
        assert!(
            body.starts_with("GOOSE_MODE: auto\nextensions:\n"),
            "{body}"
        );
        assert!(serde_saphyr::from_str::<Value>(&body).is_ok());
    }
}
