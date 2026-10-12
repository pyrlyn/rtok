// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! `rtok agents install claude --replace` (plan T9.3): drop legacy token hooks, retarget the proxy.

use anyhow::{Result, bail};
use rtok_agent_sdk::{NO_CHANGES, read_json, write_json};
use serde_json::{Value, json};

use crate::agents::apply;
use crate::config::Config;

const LEGACY: &[&str] = &[
    "rtk hook",
    "lean-ctx hook",
    "caveman-proxy",
    "caveman shrink-hook",
    "python-launcher.sh",
];
/// The ports `rtok proxy` listened on before it moved to its current default.
const LEGACY_PORTS: [u16; 2] = [8788, 8787];
const DROP_MCP: &[&str] = &["lean-ctx", "code-review-graph"];

/// Apply `--replace` to Claude settings (and `~/.claude.json` MCP map when present).
pub fn run(cfg: &Config) -> Result<String> {
    if !cfg.setup.dry_run && !cfg.setup.yes {
        bail!("--replace requires --yes (or --dry-run)");
    }
    let path = &cfg.setup.claude.settings_path;
    let mut root = read_json(path)?;
    let mut diff = rewrite(&mut root, &crate::agents::anthropic_proxy_url(cfg));
    let claude_json = &cfg.doctor.claude_json;
    if claude_json.exists() && claude_json != path {
        let mut mcp_root = read_json(claude_json)?;
        let mcp_diff = strip_mcp(&mut mcp_root);
        if !mcp_diff.is_empty() {
            diff.push_str(&format!("\n{mcp_diff}"));
            write_json(&apply(cfg), claude_json, &mcp_root, &mcp_diff)?;
        }
    }
    if diff.is_empty() {
        return Ok(NO_CHANGES.into());
    }
    write_json(&apply(cfg), path, &root, &diff)?;
    Ok(diff)
}

fn rewrite(root: &mut Value, proxy_url: &str) -> String {
    let mut lines = Vec::new();
    lines.extend(strip_hooks(root));
    if let Some(line) = retarget_proxy(root, proxy_url) {
        lines.push(line);
    }
    let mcp = strip_mcp(root);
    if !mcp.is_empty() {
        lines.push(mcp);
    }
    let rtok = count_rtok(root);
    lines.push(format!("remaining rtok hooks: {rtok}"));
    lines.join("\n")
}

fn strip_hooks(root: &mut Value) -> Vec<String> {
    let mut removed = Vec::new();
    let Some(hooks) = root.get_mut("hooks").and_then(Value::as_object_mut) else {
        return removed;
    };
    for (_, arr) in hooks.iter_mut() {
        let Some(entries) = arr.as_array_mut() else {
            continue;
        };
        entries.retain_mut(|entry| {
            let Some(list) = entry.get_mut("hooks").and_then(Value::as_array_mut) else {
                return !is_legacy_cmd(entry["command"].as_str().unwrap_or(""));
            };
            list.retain(|h| {
                let cmd = h["command"].as_str().unwrap_or("");
                if is_legacy_cmd(cmd) {
                    removed.push(format!("- {cmd}"));
                    false
                } else {
                    true
                }
            });
            !list.is_empty()
        });
    }
    removed
}

fn is_legacy_cmd(cmd: &str) -> bool {
    LEGACY.iter().any(|p| cmd.contains(p))
}

/// Point a legacy loopback `ANTHROPIC_BASE_URL` (an old rtok proxy port) at `proxy_url`, the
/// configured destination. Only the scheme and authority are replaced, so a path or query
/// stays; a foreign URL that merely contains the old digits is left alone.
fn retarget_proxy(root: &mut Value, proxy_url: &str) -> Option<String> {
    let url = root
        .pointer("/env/ANTHROPIC_BASE_URL")?
        .as_str()?
        .to_string();
    if !crate::agents::loopback_port(&url).is_some_and(|p| LEGACY_PORTS.contains(&p)) {
        return None;
    }
    // `loopback_port` parsed it, so `://` is there; the authority ends at the first `/?#`.
    let after = &url[url.find("://")? + 3..];
    let tail = &after[after.find(['/', '?', '#']).unwrap_or(after.len())..];
    let next = format!("{proxy_url}{tail}");
    root["env"]["ANTHROPIC_BASE_URL"] = json!(next.clone());
    Some(format!("ANTHROPIC_BASE_URL {url} -> {next}"))
}

fn strip_mcp(root: &mut Value) -> String {
    let Some(servers) = root.get_mut("mcpServers").and_then(Value::as_object_mut) else {
        return String::new();
    };
    let mut dropped = Vec::new();
    for name in DROP_MCP {
        if servers.remove(*name).is_some() {
            dropped.push((*name).to_string());
        }
    }
    if dropped.is_empty() {
        String::new()
    } else {
        format!("disabled MCP: {}", dropped.join(", "))
    }
}

fn count_rtok(root: &Value) -> usize {
    let Some(hooks) = root.get("hooks").and_then(Value::as_object) else {
        return 0;
    };
    hooks
        .values()
        .filter_map(Value::as_array)
        .flatten()
        .flat_map(|e| {
            e.get("hooks")
                .and_then(Value::as_array)
                .into_iter()
                .flatten()
        })
        .filter(|h| h["command"].as_str().unwrap_or("").contains("rtok hook"))
        .count()
}

#[cfg(test)]
mod tests {
    use super::*;

    const PROXY: &str = "http://127.0.0.1:8790";

    fn today() -> Value {
        json!({
            "env": {"ANTHROPIC_BASE_URL": "http://127.0.0.1:8788"},
            "mcpServers": {
                "lean-ctx": {"command": "lean-ctx"},
                "code-review-graph": {"command": "crg"},
                "serena": {"command": "serena"}
            },
            "hooks": {
                "PreToolUse": [
                    {"matcher":"Bash","hooks":[{"type":"command","command":"rtok hook PreToolUse"}]},
                    {"matcher":"Read","hooks":[{"type":"command","command":"rtok hook PreToolUse"}]},
                    {"matcher":"Bash","hooks":[{"type":"command","command":"rtk hook PreToolUse"}]},
                    {"matcher":"Bash","hooks":[{"type":"command","command":"lean-ctx hook PreToolUse"}]}
                ],
                "PostToolUse": [
                    {"matcher":"*","hooks":[{"type":"command","command":"rtok hook PostToolUse"}]},
                    {"hooks":[{"type":"command","command":"caveman-proxy"}]}
                ],
                "UserPromptSubmit": [
                    {"hooks":[{"type":"command","command":"rtok hook UserPromptSubmit"}]},
                    {"hooks":[{"type":"command","command":"caveman shrink-hook"}]}
                ],
                "SessionStart": [
                    {"hooks":[{"type":"command","command":"rtok hook SessionStart"}]},
                    {"hooks":[{"type":"command","command":"orca hook"}]}
                ],
                "PreCompact": [{"hooks":[{"type":"command","command":"rtok hook PreCompact"}]}],
                "PostCompact": [{"hooks":[{"type":"command","command":"rtok hook PostCompact"}]}],
                "Stop": [{"hooks":[{"type":"command","command":"rtok hook Stop"}]}],
                "Notification": [
                    {"hooks":[{"type":"command","command":"/opt/token-optimizer/python-launcher.sh"}]},
                    {"hooks":[{"type":"command","command":"holdmylid"}]},
                    {"hooks":[{"type":"command","command":"tokenbar"}]},
                    {"hooks":[{"type":"command","command":"cbm"}]}
                ]
            }
        })
    }

    #[test]
    fn dry_run_keeps_eight_rtok_and_non_token_hooks() {
        let mut root = today();
        let report = rewrite(&mut root, PROXY);
        assert!(report.contains("remaining rtok hooks: 8"), "{report}");
        assert_eq!(count_rtok(&root), 8);
        let raw = serde_json::to_string(&root).unwrap();
        assert!(serde_json::from_str::<Value>(&raw).is_ok());
        assert!(raw.contains("rtok hook"));
        for keep in ["orca", "holdmylid", "tokenbar", "cbm", "serena"] {
            assert!(raw.contains(keep), "kept {keep}");
        }
        for gone in [
            "rtk hook",
            "lean-ctx hook",
            "caveman-proxy",
            "caveman shrink-hook",
            "python-launcher.sh",
        ] {
            assert!(!raw.contains(gone), "removed {gone}");
        }
        assert!(!raw.contains("\"lean-ctx\""));
        assert!(!raw.contains("code-review-graph"));
        assert_eq!(root["env"]["ANTHROPIC_BASE_URL"], "http://127.0.0.1:8790");
    }

    /// T636: only a loopback URL on a legacy port is retargeted, to the configured destination;
    /// a foreign URL that merely contains the old digits is not.
    #[test]
    fn only_legacy_loopback_endpoints_are_retargeted() {
        let url = |u: &str, to: &str| {
            let mut root = json!({"env": {"ANTHROPIC_BASE_URL": u}});
            let line = retarget_proxy(&mut root, to);
            (
                root["env"]["ANTHROPIC_BASE_URL"]
                    .as_str()
                    .unwrap()
                    .to_string(),
                line.is_some(),
            )
        };
        for (old, new) in [
            ("http://127.0.0.1:8788", "http://127.0.0.1:8790"),
            (
                "http://localhost:8787/w/claude?x=1",
                "http://127.0.0.1:8790/w/claude?x=1",
            ),
            ("http://[::1]:8788/", "http://127.0.0.1:8790/"),
        ] {
            assert_eq!(url(old, PROXY), (new.to_string(), true), "{old}");
        }
        assert_eq!(
            url("http://127.0.0.1:8788", "http://127.0.0.1:9001").0,
            "http://127.0.0.1:9001"
        );
        for foreign in [
            "https://api.example/v1/8788",
            "https://api.example:8788/v1",
            "https://api.example/?p=8787",
            "https://127.0.0.1:8788@api.example/v1",
            "https://8788.example/v1",
            "http://127.0.0.1:18788",
            "http://127.0.0.1:8790",
            "not a url 8788",
        ] {
            assert_eq!(
                url(foreign, PROXY),
                (foreign.to_string(), false),
                "{foreign}"
            );
        }
        let mut root = json!({"env": {"ANTHROPIC_BASE_URL": "http://127.0.0.1:8788"}});
        assert!(retarget_proxy(&mut root, PROXY).is_some());
        assert!(retarget_proxy(&mut root, PROXY).is_none(), "idempotent");
    }

    #[test]
    fn replace_without_yes_is_err() {
        let mut c = Config::default();
        c.setup.dry_run = false;
        c.setup.yes = false;
        assert!(run(&c).unwrap_err().to_string().contains("--yes"));
    }
}
