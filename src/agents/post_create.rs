// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! T289.3 (D34): `rtok agents install|uninstall <host> --project`. Cursor, Kilo and Devin /
//! Windsurf only run a script after they create a worktree in their own pool, so rtok adds one
//! entry to the project's file for it that runs `rtok worktree adopt`. The file shapes are the
//! vendors' (`research.md` §26, "Post-create hook file shapes"). Only our entry is written or
//! removed; every other byte of the file stays (host-config rule), and a file we leave empty
//! goes with it.

use std::path::{Path, PathBuf};

use anyhow::{Context, Result, bail};
use rtok_agent_sdk::{Apply, write};
use serde_json::{Value, json};

use super::jsonc;
use crate::config::Config;

/// The hosts that have a post-create script, in the order they are documented.
pub const HOSTS: [&str; 4] = ["cursor", "kilo", "windsurf", "devin"];

const CURSOR_FILE: &str = ".cursor/worktrees.json";
/// Unix and Windows keys take precedence over the generic one on their OS, so our command goes
/// into every one that holds a list of commands.
const CURSOR_KEYS: [&str; 3] = [
    "setup-worktree",
    "setup-worktree-unix",
    "setup-worktree-windows",
];
const DEVIN_FILE: &str = ".devin/hooks.json";
const WINDSURF_FILE: &str = ".windsurf/hooks.json";
const HOOK: [&str; 2] = ["hooks", "post_setup_worktree"];
const KILO_FILES: [&str; 2] = [".kilo/setup-script", ".kilo/setup-script.sh"];
const KILO_BEGIN: &str = "# >>> rtok worktree adopt (rtok agents install kilo --project) >>>";
const KILO_END: &str = "# <<< rtok worktree adopt <<<";
const SHEBANG: &str = "#!/bin/sh\n";

/// The line a host runs after it created a worktree. Fail open: the resolver `exec`s rtok, so
/// a refused `adopt` (no single live agent) would be the script's exit code; the subshell and
/// `|| true` keep it 0, because Kilo keeps a worktree whose setup failed and Cursor's handling
/// of a failure is not documented.
fn command() -> String {
    if cfg!(windows) {
        format!("{} worktree adopt", super::rtok_hook_bin())
    } else {
        posix()
    }
}

fn posix() -> String {
    format!(
        "( {} ) || true",
        super::hook_resolver("worktree adopt", None)
    )
}

/// Whether `cmd` is a command [`command`] wrote, under any rtok path.
fn ours(cmd: &str) -> bool {
    let Some((head, _)) = cmd.split_once(" worktree adopt") else {
        return false;
    };
    let head = head.trim_start_matches("( ");
    head.starts_with("command -v rtok") || super::is_rtok_bin(super::unquote_bin(head))
}

fn is_ours_item(v: &Value) -> bool {
    v.as_str()
        .or_else(|| v["command"].as_str())
        .is_some_and(ours)
}

/// `rtok agents install|uninstall <hosts> --project`: one report line per host.
pub fn run(cfg: &Config, hosts: &[String], remove: bool, root: &Path) -> Result<String> {
    if let Some(bad) = hosts.iter().find(|h| !HOSTS.contains(&h.as_str())) {
        bail!(
            "{bad} has no post-create script to add rtok to; --project works for {}",
            HOSTS.join(", ")
        );
    }
    // These files belong to the project and are normally committed, so git is the undo; a
    // `_backup` folder beside them would only show up as untracked noise in the repository.
    let apply = Apply {
        backup: false,
        ..super::apply(cfg)
    };
    let mut out = String::new();
    for host in hosts {
        let (file, note) = match host.as_str() {
            "cursor" => cursor(&apply, root, remove)?,
            "kilo" => kilo(&apply, root, remove)?,
            _ => hooks_json(&apply, root, remove)?,
        };
        let rel = file.strip_prefix(root).unwrap_or(&file);
        out.push_str(&format!("{host}: {}: {note}\n", rel.display()));
    }
    Ok(out)
}

fn verb(apply: &Apply, remove: bool, did: bool) -> String {
    match (did, apply.dry_run, remove) {
        (false, ..) => rtok_agent_sdk::NO_CHANGES.into(),
        (true, true, false) => "would add".into(),
        (true, true, true) => "would remove".into(),
        (true, false, false) => "added".into(),
        (true, false, true) => "removed".into(),
    }
}

/// Write `text` over `path`, or, when a removal left nothing in it, remove the file: it only
/// existed for our entry.
fn save(apply: &Apply, path: &Path, text: &str, empty: bool, report: &str) -> Result<()> {
    if !(empty && apply.writes(report)) {
        return write(apply, path, text, report);
    }
    std::fs::remove_file(path).with_context(|| path.display().to_string())
}

fn read_doc(raw: &str, path: &Path) -> Result<Value> {
    if raw.trim().is_empty() {
        return Ok(json!({}));
    }
    jsonc::parse(raw).with_context(|| path.display().to_string())
}

/// `.cursor/worktrees.json`: our command in each `setup-worktree*` list. A key holding a script
/// path is not ours to edit, so it gets a note instead of a change.
fn cursor(apply: &Apply, root: &Path, remove: bool) -> Result<(PathBuf, String)> {
    let path = root.join(CURSOR_FILE);
    let raw = jsonc::read_or_empty(&path)?;
    let doc = read_doc(&raw, &path)?;
    let present: Vec<&str> = CURSOR_KEYS
        .into_iter()
        .filter(|k| doc.get(*k).is_some())
        .collect();
    let (mut text, mut did) = (raw.clone(), false);
    let mut notes = Vec::new();
    if remove {
        for key in present {
            let (next, n) = jsonc::pull_items(&text, &path, &[key], is_ours_item)?;
            (text, did) = (next, did || n > 0);
        }
    } else {
        let lists: Vec<&str> = present
            .iter()
            .copied()
            .filter(|k| doc[*k].is_array())
            .collect();
        for key in present.iter().filter(|k| !lists.contains(k)) {
            notes.push(format!(
                "{key} runs a script; add `{}` to it by hand",
                command()
            ));
        }
        let targets = if present.is_empty() {
            vec![CURSOR_KEYS[0]]
        } else {
            lists
        };
        for key in targets {
            let has = doc[key]
                .as_array()
                .is_some_and(|a| a.iter().any(is_ours_item));
            if !has {
                text = jsonc::push_item(&text, &path, &[key], &json!(command()))?;
                did = true;
            }
        }
    }
    let note = verb(apply, remove, did);
    save(
        apply,
        &path,
        &text,
        did && jsonc::is_empty_at(&text, &[]),
        &note,
    )?;
    Ok((path, join_notes(note, notes)))
}

fn join_notes(note: String, notes: Vec<String>) -> String {
    notes.into_iter().fold(note, |acc, n| format!("{acc}; {n}"))
}

/// Whether the hooks file at `path` defines any hook, the test the host applies before it falls
/// back to the legacy file.
fn defines_hooks(path: &Path) -> bool {
    jsonc::read_or_empty(path)
        .ok()
        .and_then(|raw| jsonc::parse(&raw).ok())
        .is_some_and(|d| d["hooks"].as_object().is_some_and(|h| !h.is_empty()))
}

/// Devin and Windsurf share one hook: `post_setup_worktree` in the workspace hooks file the host
/// reads today. `.devin/hooks.json` wins when it defines hooks; a legacy `.windsurf/hooks.json`
/// is read only while it does not, so creating the new file next to a legacy one that has hooks
/// would silently switch them off.
fn hooks_json(apply: &Apply, root: &Path, remove: bool) -> Result<(PathBuf, String)> {
    let (devin, legacy) = (root.join(DEVIN_FILE), root.join(WINDSURF_FILE));
    if remove {
        let mut done = None;
        for path in [&devin, &legacy] {
            let raw = jsonc::read_or_empty(path)?;
            if raw.trim().is_empty() {
                continue;
            }
            let (text, n) = jsonc::pull_items(&raw, path, &HOOK, is_ours_item)?;
            let note = verb(apply, true, n > 0);
            save(
                apply,
                path,
                &text,
                n > 0 && jsonc::is_empty_at(&text, &[]),
                &note,
            )?;
            if n > 0 {
                done = Some((path.clone(), note));
            }
        }
        return Ok(done.unwrap_or((devin, rtok_agent_sdk::NO_CHANGES.into())));
    }
    let path = if !defines_hooks(&devin) && defines_hooks(&legacy) {
        legacy
    } else {
        devin
    };
    let raw = jsonc::read_or_empty(&path)?;
    let doc = read_doc(&raw, &path)?;
    let has = doc["hooks"]["post_setup_worktree"]
        .as_array()
        .is_some_and(|a| a.iter().any(is_ours_item));
    let text = if has {
        raw
    } else {
        jsonc::push_item(&raw, &path, &HOOK, &json!({ "command": command() }))?
    };
    let note = verb(apply, false, !has);
    write(apply, &path, &text, &note)?;
    Ok((path, note))
}

/// The block that runs `rtok worktree adopt` in a subshell: Kilo runs the file as one script,
/// and the resolver ends in `exec`, which would otherwise replace the script.
fn kilo_block() -> String {
    format!("{KILO_BEGIN}\n{}\n{KILO_END}\n", posix())
}

/// `.kilo/setup-script`: a marked block right after the shebang, so the claim exists even when a
/// later line of the user's script fails or exits. `.kilo/setup-script.sh` is edited instead when
/// it is the file Kilo reads (it looks for `setup-script` first).
fn kilo(apply: &Apply, root: &Path, remove: bool) -> Result<(PathBuf, String)> {
    let plain = root.join(KILO_FILES[0]);
    let path = if !plain.exists() && root.join(KILO_FILES[1]).exists() {
        root.join(KILO_FILES[1])
    } else {
        plain
    };
    let raw = jsonc::read_or_empty(&path)?;
    let span = raw.find(KILO_BEGIN).map(|b| {
        let end = raw[b..].find(KILO_END).map(|e| b + e + KILO_END.len());
        (b, end)
    });
    let text = match (span, remove) {
        (Some((_, None)), _) => bail!(
            "{}: the rtok block has no end marker `{KILO_END}`; fix it by hand",
            path.display()
        ),
        (Some((b, Some(e))), true) => {
            let e = e + usize::from(raw[e..].starts_with('\n'));
            format!("{}{}", &raw[..b], &raw[e..])
        }
        (None, true) => raw.clone(),
        (Some((b, Some(e))), false) => {
            let e = e + usize::from(raw[e..].starts_with('\n'));
            format!("{}{}{}", &raw[..b], kilo_block(), &raw[e..])
        }
        (None, false) if raw.is_empty() => format!("{SHEBANG}{}", kilo_block()),
        (None, false) => {
            let at = if raw.starts_with("#!") {
                raw.find('\n').map_or(raw.len(), |n| n + 1)
            } else {
                0
            };
            let (head, tail) = raw.split_at(at);
            let nl = if head.is_empty() || head.ends_with('\n') {
                ""
            } else {
                "\n"
            };
            format!("{head}{nl}{}{tail}", kilo_block())
        }
    };
    let note = verb(apply, remove, text != raw);
    save(
        apply,
        &path,
        &text,
        remove && text != raw && text.trim() == SHEBANG.trim(),
        &note,
    )?;
    Ok((path, note))
}
