// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! PreCompact checkpoint + compact restore (plan T2.5).

use rtok_plugin_sdk::{Class, Ctx, Injection, Measurement};
use serde_json::Value;
use std::collections::{BTreeSet, VecDeque};
use std::io::BufRead;
use std::path::Path;

/// Parsed compact snapshot.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Checkpoint {
    pub prompts: Vec<String>,
    pub paths: Vec<String>,
    pub errors: Vec<String>,
    /// Skills the host injected before compaction, `(name, body bytes)` per invocation
    /// (plan T62.2): the body is gone after compaction and the model must know what it
    /// had, not re-invoke everything.
    pub skills: Vec<(String, u64)>,
    /// Archive ids of this session's live-window tool results, newest first (T58.2).
    pub ids: Vec<String>,
    /// `(tool, bytes)` aligned with [`Self::ids`].
    id_meta: Vec<(String, u64)>,
    /// User-text records taken as typed prompts over the whole transcript, not just the
    /// 20 kept (T419). Never rendered: the restore stays byte-identical.
    pub typed: u64,
    /// User-text records dropped as host-written (T417's filter), same span as `typed`.
    pub skipped: u64,
}

impl Checkpoint {
    pub fn render(&self) -> String {
        let mut s = String::from("checkpoint\n");
        for p in &self.prompts {
            s.push_str("- ");
            s.push_str(p);
            s.push('\n');
        }
        if !self.skills.is_empty() {
            let list: Vec<String> = self
                .skills
                .iter()
                .map(|(n, b)| format!("{n} ({:.1} KB)", *b as f64 / 1024.0))
                .collect();
            s.push_str("skills loaded before compaction: ");
            s.push_str(&list.join(", "));
            s.push_str(" — re-invoke only what the next step needs\n");
        }
        for p in &self.paths {
            s.push_str("path ");
            s.push_str(p);
            s.push('\n');
        }
        for e in &self.errors {
            s.push_str("err ");
            s.push_str(e);
            s.push('\n');
        }
        for (id, (tool, bytes)) in self.ids.iter().zip(&self.id_meta) {
            s.push_str("id ");
            s.push_str(id);
            s.push(' ');
            s.push_str(tool);
            s.push(' ');
            s.push_str(&bytes.to_string());
            s.push('\n');
        }
        s
    }
}

/// Last 20 user prompts (≤ 300 chars), file paths, and the last 8 error lines from a JSONL
/// transcript held entirely in memory. [`extract_path`] is the streamed twin `write` uses on
/// a transcript file, so a real (possibly hundreds-of-MB) session never sits in memory at
/// once (T203); both share [`extract_lines`], so the two extractions never drift apart.
pub fn extract(jsonl: &str) -> Checkpoint {
    extract_lines(jsonl.as_bytes().lines())
}

/// [`extract`], streamed line by line from `path` instead of read whole. Returns the default
/// (empty) checkpoint when the file is missing or unreadable — the same fallback
/// `read_to_string(..).unwrap_or_default()` gave before T203.
fn extract_path(path: &Path) -> Checkpoint {
    match std::fs::File::open(path) {
        Ok(f) => extract_lines(std::io::BufReader::new(f).lines()),
        Err(_) => Checkpoint::default(),
    }
}

/// [`extract_lines_with`] with the prefilter on — what `extract`/`extract_path` use.
fn extract_lines<I: Iterator<Item = std::io::Result<String>>>(lines: I) -> Checkpoint {
    extract_lines_with(lines, true)
}

/// Shared extraction loop: bounded state only (last 20 prompts, last 8 errors, the path set,
/// skill bodies), so memory never grows with the transcript's length. With `filter` on,
/// [`worth_parsing`] skips the serde_json parse (and the tree walk after it) on a line that
/// cannot hold a path, an error, a prompt or a skill body — the lever that keeps a
/// multi-hundred-MB transcript, most of which is large tool output with none of those,
/// inside a hook's time budget. `filter = false` runs the identical loop unfiltered, so
/// `prefilter_is_a_true_superset_*` below can assert the two agree instead of trusting that
/// claim to a comment.
fn extract_lines_with<I: Iterator<Item = std::io::Result<String>>>(
    lines: I,
    filter: bool,
) -> Checkpoint {
    let mut prompts: VecDeque<String> = VecDeque::new();
    let mut paths = BTreeSet::new();
    let mut errors = VecDeque::new();
    let mut skills = Vec::new();
    let (mut typed, mut skipped) = (0, 0);
    for line in lines {
        let Ok(line) = line else { continue };
        if filter && !worth_parsing(&line) {
            continue;
        }
        let Ok(v) = serde_json::from_str::<Value>(&line) else {
            continue;
        };
        walk(&v, &mut paths, &mut |s| {
            // The three spellings that occur in compiler, test and runtime output; no
            // lowercased copy of every transcript string.
            if ["error", "Error", "ERROR"].iter().any(|n| s.contains(n)) {
                if errors.len() == 8 {
                    errors.pop_front();
                }
                errors.push_back(s.chars().take(200).collect());
            }
        });
        if let Some(s) = skill_body(&v) {
            skills.push(s);
        } else {
            match user_prompt(&v) {
                Some(UserText::Typed(p)) => {
                    typed += 1;
                    if prompts.len() == 20 {
                        prompts.pop_front();
                    }
                    prompts.push_back(p);
                }
                Some(UserText::Host) => skipped += 1,
                None => {}
            }
        }
    }
    Checkpoint {
        prompts: prompts.into(),
        paths: paths.into_iter().collect(),
        errors: errors.into(),
        skills,
        typed,
        skipped,
        ..Default::default()
    }
}

/// A raw JSONL line that cannot contribute anything [`extract_lines_with`] keeps.
///
/// `file_path` and the three error spellings survive JSON string escaping unchanged, so
/// checking for them is a safe superset of what the tree walk would find. A `"type":"user"`
/// record is only interesting through [`user_prompt`]/[`skill_body`], and both read only
/// through [`user_text`]: a bare string `message.content`, or an array with a
/// `"type":"text"` block. Neither reads a `tool_result` block (marked by `"tool_use_id"`),
/// which is the shape that dominates a real transcript's bytes — a large Bash/Read result is
/// echoed back as a `user` turn, not typed by anyone — so that is the one case this filter
/// excludes. `skill_body` also needs `"isMeta":true` and `"sourceToolUseID"`, both specific
/// enough that a false hit in ordinary transcript text is effectively impossible, so a line
/// carrying both is always let through.
///
/// `prefilter_is_a_true_superset_on_fixtures_and_a_large_transcript` (below) checks this
/// claim empirically — with the filter on and off — rather than trusting it to this comment.
fn worth_parsing(line: &str) -> bool {
    if line.contains("file_path")
        || line.contains("error")
        || line.contains("Error")
        || line.contains("ERROR")
    {
        return true;
    }
    if !line.contains("\"type\":\"user\"") {
        return false;
    }
    if line.contains("\"isMeta\":true") && line.contains("\"sourceToolUseID\"") {
        return true;
    }
    !line.contains("\"tool_use_id\"")
}

/// A skill body the host injected: a `user` record flagged `isMeta` with a
/// `sourceToolUseID` whose text opens with `Base directory for this skill: <dir>`
/// (Claude Code, checked 2026-09-17). The name is the directory's last component so a
/// plugin skill and a user skill resolve the same way; the size is the injected text.
fn skill_body(v: &Value) -> Option<(String, u64)> {
    if v.get("type").and_then(Value::as_str) != Some("user")
        || v.get("isMeta").and_then(Value::as_bool) != Some(true)
        || v.get("sourceToolUseID").is_none()
    {
        return None;
    }
    let text = user_text(v)?;
    let dir = text
        .lines()
        .next()?
        .strip_prefix("Base directory for this skill: ")?
        .trim_end_matches(['/', '\\']);
    let name = dir.rsplit(['/', '\\']).next().filter(|n| !n.is_empty())?;
    Some((name.to_string(), text.len() as u64))
}

/// Text the human typed, without the host context wrapped around it (T417). Most `user`
/// records in a live Claude Code transcript are host-injected — task notifications,
/// sub-agent hand-backs, CI events, skill bodies, the compaction summary — and quoting them
/// would fill the 20-prompt window with noise that crowds out what the user asked for.
fn user_prompt(v: &Value) -> Option<UserText> {
    if v.get("type").and_then(Value::as_str) != Some("user") {
        return None;
    }
    let raw = user_text(v)?;
    // A tool-result echo has no text block and joins to "": not a prompt of anyone's,
    // so it must not count as a skipped host record either.
    if raw.trim().is_empty() {
        return None;
    }
    if host_injected(v) {
        return Some(UserText::Host);
    }
    let t = strip_reminders(&raw);
    let t = t.trim();
    if t.is_empty() || HOST_OPENERS.iter().any(|o| t.starts_with(o)) {
        Some(UserText::Host)
    } else {
        Some(UserText::Typed(t.chars().take(300).collect()))
    }
}

/// What [`user_prompt`] made of a user record that carries text; the split is T419's
/// quality signal, so a record without text (a tool result) is neither.
#[derive(Debug, PartialEq, Eq)]
enum UserText {
    Typed(String),
    Host,
}

/// Record-level marks Claude Code puts on what it injected (checked 2026-10-05): `isMeta`
/// (skill bodies, peer messages, continuation nudges), `isCompactSummary`, and an `origin`
/// whose kind is anything but `human` (`task-notification`, `peer`).
fn host_injected(v: &Value) -> bool {
    let flag = |k: &str| v.get(k).and_then(Value::as_bool) == Some(true);
    if flag("isMeta") || flag("isCompactSummary") {
        return true;
    }
    v.get("origin")
        .and_then(|o| o.get("kind"))
        .and_then(Value::as_str)
        .is_some_and(|k| k != "human")
}

/// Openings of host-written text in records that carry no `origin` — CI monitor events,
/// local command echoes, interrupts, and transcripts from before Claude Code added `origin`.
const HOST_OPENERS: [&str; 7] = [
    "<task-notification>",
    "<ci-monitor-event>",
    "<local-command-stdout>",
    "<local-command-stderr>",
    "<local-command-caveat>",
    "<agent-message",
    "[Request interrupted by user",
];

/// `text` without its `<system-reminder>` blocks: the host prepends them to typed prompts,
/// and a block with no closing tag runs to the end because nothing after it was typed.
fn strip_reminders(text: &str) -> String {
    const OPEN: &str = "<system-reminder>";
    const CLOSE: &str = "</system-reminder>";
    let mut out = String::with_capacity(text.len());
    let mut rest = text;
    while let Some(i) = rest.find(OPEN) {
        out.push_str(&rest[..i]);
        rest = match rest[i..].find(CLOSE) {
            Some(j) => &rest[i + j + CLOSE.len()..],
            None => "",
        };
    }
    out.push_str(rest);
    out
}

/// Text blocks of a user record joined by newlines; `None` when there are none.
fn user_text(v: &Value) -> Option<String> {
    let c = v.pointer("/message/content")?;
    let raw = match c {
        Value::String(s) => s.clone(),
        Value::Array(a) => a
            .iter()
            .filter_map(|b| {
                if b.get("type").and_then(Value::as_str) != Some("text") {
                    return None;
                }
                b.get("text").and_then(Value::as_str).map(str::to_string)
            })
            .collect::<Vec<_>>()
            .join("\n"),
        _ => return None,
    };
    Some(raw)
}

fn walk(v: &Value, paths: &mut BTreeSet<String>, text: &mut impl FnMut(&str)) {
    match v {
        Value::String(s) => text(s),
        Value::Object(m) => {
            if let Some(p) = m.get("file_path").and_then(Value::as_str)
                && !p.is_empty()
            {
                paths.insert(p.to_string());
            }
            for c in m.values() {
                walk(c, paths, text);
            }
        }
        Value::Array(a) => {
            for c in a {
                walk(c, paths, text);
            }
        }
        _ => {}
    }
}

/// Note kind per session: compaction keeps the session id, and two hosts compacting at
/// once must not restore each other's checkpoint.
fn kind(cx: &Ctx) -> String {
    format!("checkpoint:{}", cx.session())
}

/// Read `transcript_path`, store a `notes` row `kind=checkpoint:<session>`.
pub fn save(transcript_path: &str, cx: &Ctx) -> anyhow::Result<Checkpoint> {
    write(transcript_path, cx, &kind(cx), Some("rtok"))
}

/// SessionEnd: same extractor and render as [`save`], kind `session:<session>`.
pub fn save_session(transcript_path: &str, cx: &Ctx) -> anyhow::Result<Checkpoint> {
    let project = project_of_cx(cx);
    write(
        transcript_path,
        cx,
        &format!("session:{}", cx.session()),
        project.as_deref(),
    )
}

fn write(
    transcript_path: &str,
    cx: &Ctx,
    kind: &str,
    project: Option<&str>,
) -> anyhow::Result<Checkpoint> {
    let mut cp = extract_path(Path::new(transcript_path));
    attach_ids(&mut cp, cx);
    // T209: `notes_topic` now enforces one row per (project, kind, title) at the
    // database level. `kind` already scopes this to the session, and `offer`/
    // `offer_session` only ever read the newest row back (`latest_note`), so a repeat
    // PreCompact/SessionEnd in the same session replaces the checkpoint instead of
    // piling up dead history that nothing reads.
    cx.upsert_note(project, kind, "compact", &cp.render())?;
    // T419: a quality signal, not a saving, so it stays out of the Measurement ledger; one
    // row per checkpoint kind, replaced like the note above. Best effort: the stats line
    // is not worth failing the checkpoint over.
    let counts = serde_json::json!({ "typed": cp.typed, "skipped": cp.skipped });
    let _ = cx.plugin_state_set("memory", kind, &counts.to_string());
    Ok(cp)
}

/// Typed and skipped totals over the plugin-state rows [`write`] left (T419). PreCompact
/// and SessionEnd of one session each write a row and the later transcript holds the
/// earlier one, so a session counts once, by its larger row. A row that does not parse is
/// skipped: the value is stored text, not trusted.
pub fn prompt_counts(rows: &[(String, String)]) -> (u64, u64) {
    let mut per_session: std::collections::BTreeMap<&str, (u64, u64)> = Default::default();
    for (key, value) in rows {
        let Some((_, session)) = key
            .split_once(":checkpoint:")
            .or_else(|| key.split_once(":session:"))
        else {
            continue;
        };
        let Ok(v) = serde_json::from_str::<Value>(value) else {
            continue;
        };
        let n = |k: &str| v.get(k).and_then(Value::as_u64).unwrap_or(0);
        let row = (n("typed"), n("skipped"));
        let best = per_session.entry(session).or_default();
        if row.0.saturating_add(row.1) > best.0.saturating_add(best.1) {
            *best = row;
        }
    }
    per_session.values().fold((0, 0), |(t, s), (rt, rs)| {
        (t.saturating_add(*rt), s.saturating_add(*rs))
    })
}

fn project_of_cx(cx: &Ctx) -> Option<String> {
    crate::config::layers::git_root(Path::new(cx.cwd()?))?
        .file_name()
        .map(|n| n.to_string_lossy().into_owned())
}

fn checkpoint_cap(cx: &Ctx) -> u32 {
    cx.plugin_config::<crate::config::Memory>("memory")
        .checkpoint_tokens
        .max(1)
}

/// Newest live archive ids that still fit `plugins.memory.checkpoint_tokens`. Reads through
/// the hook `Runtime`'s own store (T203) instead of opening a second one on the same file.
fn attach_ids(cp: &mut Checkpoint, cx: &Ctx) {
    let Ok(rows) = cx.session_live_archives(cx.session()) else {
        return;
    };
    let cap = checkpoint_cap(cx);
    for (id, tool, bytes) in rows {
        let bytes = u64::try_from(bytes).unwrap_or(u64::MAX);
        cp.ids.push(id);
        cp.id_meta.push((tool, bytes));
        if cx.estimate(&cp.render(), Class::Prose) > cap {
            cp.ids.pop();
            cp.id_meta.pop();
            break;
        }
    }
}

/// Latest checkpoint of this session as an injection, capped at
/// `plugins.memory.checkpoint_tokens`.
pub fn offer(cx: &Ctx) -> Option<Injection> {
    render_offer(cx, cx.latest_note(&kind(cx)).ok().flatten()?)
}

/// Newest `session:*` note of the hook cwd's project, same render and budget as [`offer`].
/// Reads through the hook `Runtime`'s own store (T203) instead of opening a third one.
pub fn offer_session(cx: &Ctx) -> Option<Injection> {
    let raw = cx
        .latest_session_note(project_of_cx(cx).as_deref())
        .ok()
        .flatten()?;
    let inj = render_offer(cx, raw.clone())?;
    let _ = cx.record(&Measurement {
        plugin: "memory",
        kind: "handoff",
        before_bytes: raw.len() as u64,
        after_bytes: inj.text.len() as u64,
        est_before: cx.estimate(&raw, Class::Prose),
        est_after: cx.estimate(&inj.text, Class::Prose),
        ref_id: None,
        call_id: None,
    });
    Some(inj)
}

fn render_offer(cx: &Ctx, text: String) -> Option<Injection> {
    let cap = checkpoint_cap(cx);
    let text = crate::plugin::fit_budget(cx, &text, Class::Prose, cap);
    (!text.is_empty()).then_some(Injection {
        plugin: "inject",
        text,
        priority: 9,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    const FIXTURE: &str = r#"{"type":"user","message":{"role":"user","content":"edit the three files"}}
{"type":"assistant","message":{"content":[{"type":"tool_use","name":"Read","input":{"file_path":"src/a.rs"}},{"type":"tool_use","name":"Read","input":{"file_path":"src/b.rs"}},{"type":"tool_use","name":"Read","input":{"file_path":"src/c.rs"}}]}}
{"type":"user","message":{"role":"user","content":[{"type":"text","text":"still failing with error: boom"}]}}
"#;

    #[test]
    fn fixture_has_three_paths_and_compact_injects_under_budget() {
        let cp = extract(FIXTURE);
        assert_eq!(cp.paths, ["src/a.rs", "src/b.rs", "src/c.rs"]);
        let dir = std::env::temp_dir().join("rtok-t25-fixture-has-three-paths");
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(dir.join("t.jsonl"), FIXTURE).unwrap();
        let cfg = crate::testutil::config_in(&dir);
        let pre = serde_json::json!({"hook_event_name":"PreCompact","session_id":"t25","transcript_path":dir.join("t.jsonl").to_str().unwrap(),"trigger":"auto"});
        let mut out = Vec::new();
        crate::hooks::run("PreCompact", pre.to_string().as_bytes(), &mut out, &cfg);
        assert_eq!(out, b"{}");
        let body = crate::store::Store::open(&cfg.core.db_path)
            .unwrap()
            .latest_note("checkpoint:t25")
            .unwrap()
            .expect("note");
        let restore = |session: &str, out: &mut Vec<u8>| {
            let start = serde_json::json!({"hook_event_name":"SessionStart","session_id":session,"source":"compact"});
            out.clear();
            crate::hooks::run(
                "SessionStart",
                start.to_string().as_bytes(),
                &mut *out,
                &cfg,
            );
            serde_json::from_slice::<serde_json::Value>(out).unwrap()["hookSpecificOutput"]
                ["additionalContext"]
                .as_str()
                .unwrap_or("")
                .to_string()
        };
        let text = restore("t25", &mut out);
        let cx = crate::plugin::Runtime::in_memory("budget").unwrap();
        for p in ["src/a.rs", "src/b.rs", "src/c.rs"] {
            assert!(body.contains(p) && text.contains(p), "{body}\n{text}");
        }
        assert!(cx.estimate(&text, Class::Prose) <= cx.config.plugins.memory.checkpoint_tokens);
        // Another session compacting against the same store gets nothing of t25's.
        let other = restore("t25-other", &mut out);
        assert!(!other.contains("src/a.rs"), "{other}");
    }

    /// The injected body is a skill, never a prompt: it lands on the skills line with
    /// its size, and the prompt list keeps only what the human typed.
    #[test]
    fn injected_skill_bodies_are_listed_not_quoted() {
        let body = "Base directory for this skill: /home/u/.claude/skills/pixel\n\n# Pixel\n"
            .to_string()
            + &"x".repeat(5000);
        let lines = [
            r#"{"type":"user","message":{"content":"make it blue"}}"#.to_string(),
            serde_json::json!({"type":"user","isMeta":true,"sourceToolUseID":"toolu_1","message":{"content":[{"type":"text","text":body}]}}).to_string(),
            serde_json::json!({"type":"user","isMeta":true,"sourceToolUseID":"toolu_2","message":{"content":[{"type":"text","text":"Base directory for this skill: C:\\u\\.claude\\plugins\\cache\\p\\1.0\\skills\\ponytail\n\n# P"}]}}).to_string(),
            // `isMeta` without a source tool is not a skill, and not typed either (T417).
            r#"{"type":"user","isMeta":true,"message":{"content":"Base directory for this skill: /x/y"}}"#.to_string(),
        ]
        .join("\n");
        let cp = extract(&lines);
        assert_eq!(cp.skills.len(), 2, "{:?}", cp.skills);
        assert_eq!(cp.skills[0].0, "pixel");
        assert_eq!(cp.skills[0].1, body.len() as u64);
        assert_eq!(cp.skills[1].0, "ponytail");
        assert_eq!(cp.prompts, ["make it blue"]);
        let text = cp.render();
        assert!(text.contains("skills loaded before compaction: pixel (5.0 KB), ponytail (0.1 KB) — re-invoke only what the next step needs\n"), "{text}");
        assert!(!text.contains("xxxx"), "{text}");
    }

    /// One record per host-injected shape seen in real Claude Code transcripts (T417,
    /// 2026-10-05), interleaved with what the human typed.
    const HOST_FIXTURE: &str = r#"{"type":"user","origin":{"kind":"human"},"message":{"content":"first typed"}}
{"type":"user","origin":{"kind":"task-notification","producer":"session-task"},"message":{"content":"<task-notification>\n<task-id>b1</task-id>\n<status>completed</status>\n</task-notification>"}}
{"type":"user","isMeta":true,"origin":{"kind":"peer","from":"a1"},"message":{"content":[{"type":"text","text":"Another Claude session sent a message:\n<agent-message from=\"a1\">done</agent-message>"}]}}
{"type":"user","isCompactSummary":true,"isVisibleInTranscriptOnly":true,"message":{"content":"This session is being continued from a previous conversation."}}
{"type":"user","isMeta":true,"message":{"content":"Your response above was cut off."}}
{"type":"user","message":{"content":"<ci-monitor-event>\"Auto-fix\" is on</ci-monitor-event>"}}
{"type":"user","message":{"content":"<local-command-stdout>ok</local-command-stdout>"}}
{"type":"user","isMeta":true,"message":{"content":"<local-command-caveat>Caveat</local-command-caveat>"}}
{"type":"user","message":{"content":[{"type":"text","text":"[Request interrupted by user for tool use]"}]}}
{"type":"user","message":{"content":"<task-notification>\n<task-id>old</task-id>\n</task-notification>"}}
{"type":"user","message":{"content":"<system-reminder>\nonly host context\n</system-reminder>"}}
{"type":"user","origin":{"kind":"human"},"message":{"content":[{"type":"text","text":"<system-reminder>\nThe user started task_1.\n</system-reminder>\nsecond typed"}]}}
{"type":"user","origin":{"kind":"human"},"message":{"content":"third <system-reminder>a</system-reminder>typed<system-reminder>\nunclosed tail"}}
"#;

    #[test]
    fn host_injected_records_are_not_prompts() {
        let cp = extract(HOST_FIXTURE);
        assert_eq!(cp.prompts, ["first typed", "second typed", "third typed"]);
        assert_eq!((cp.typed, cp.skipped), (3, 10), "T419 counts every record");
        let text = cp.render();
        for noise in [
            "task-notification",
            "agent-message",
            "continued from",
            "cut off",
            "ci-monitor",
            "local-command",
            "interrupted",
            "system-reminder",
            "host context",
            "task_1",
            "unclosed",
        ] {
            assert!(!text.contains(noise), "{noise} leaked into\n{text}");
        }
    }

    /// The 20-prompt window is spent on typed prompts only: notifications in between
    /// neither take a slot nor push a typed prompt out.
    #[test]
    fn prompt_window_counts_only_typed_prompts() {
        let lines = (0..25)
            .flat_map(|i| {
                [
                    format!(r#"{{"type":"user","origin":{{"kind":"human"}},"message":{{"content":"typed {i}"}}}}"#),
                    format!(r#"{{"type":"user","origin":{{"kind":"task-notification"}},"message":{{"content":"<task-notification>{i}</task-notification>"}}}}"#),
                ]
            })
            .collect::<Vec<_>>()
            .join("\n");
        let cp = extract(&lines);
        let want: Vec<String> = (5..25).map(|i| format!("typed {i}")).collect();
        assert_eq!(cp.prompts, want);
        assert_eq!(
            (cp.typed, cp.skipped),
            (25, 25),
            "the counts span the transcript, not the window"
        );
    }

    /// T419: the counts are a side channel — the rendered checkpoint is the same text with
    /// or without them, so the restore injection stays byte-identical.
    #[test]
    fn counts_never_reach_the_render() {
        let cp = extract(HOST_FIXTURE);
        let bare = Checkpoint {
            typed: 0,
            skipped: 0,
            ..cp.clone()
        };
        assert_eq!(cp.render(), bare.render());
    }

    /// T419: a tool-result echo is a `user` record with no text block — neither typed nor
    /// host-written, so it moves neither count.
    #[test]
    fn tool_result_records_count_as_neither() {
        let cp = extract(
            r#"{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t1","content":"ok"}]}}
{"type":"user","message":{"content":""}}"#,
        );
        assert_eq!((cp.typed, cp.skipped), (0, 0));
    }

    /// T419: one session counts once (its larger row), rows from other plugins' keys and
    /// rows that do not parse are skipped.
    #[test]
    fn prompt_counts_take_each_sessions_largest_row() {
        let rows = |r: &[(&str, &str)]| {
            r.iter()
                .map(|(k, v)| (k.to_string(), v.to_string()))
                .collect::<Vec<_>>()
        };
        let got = prompt_counts(&rows(&[
            ("plugin:memory:checkpoint:s1", r#"{"typed":2,"skipped":3}"#),
            ("plugin:memory:session:s1", r#"{"typed":5,"skipped":4}"#),
            ("plugin:memory:session:s2", r#"{"typed":1,"skipped":0}"#),
            ("plugin:memory:session:s3", "not json"),
            ("plugin:memory:other", r#"{"typed":99,"skipped":99}"#),
        ]));
        assert_eq!(got, (6, 4));
        let huge = format!(r#"{{"typed":{},"skipped":1}}"#, u64::MAX);
        let got = prompt_counts(&rows(&[
            ("plugin:memory:session:a", huge.as_str()),
            ("plugin:memory:session:b", huge.as_str()),
        ]));
        assert_eq!(got, (u64::MAX, 2), "a forged row saturates, never panics");
        assert_eq!(prompt_counts(&[]), (0, 0));
    }

    #[test]
    fn strip_reminders_cuts_every_block() {
        assert_eq!(strip_reminders("a"), "a");
        assert_eq!(
            strip_reminders(
                "<system-reminder>x</system-reminder>a<system-reminder>y</system-reminder>b"
            ),
            "ab"
        );
        assert_eq!(strip_reminders("a<system-reminder>never closed"), "a");
        assert_eq!(
            strip_reminders("a</system-reminder>b"),
            "a</system-reminder>b"
        );
    }

    #[test]
    fn errors_keep_the_last_eight() {
        let lines = (0..12)
            .map(|i| format!(r#"{{"type":"assistant","message":{{"content":"Error {i}"}}}}"#))
            .collect::<Vec<_>>()
            .join("\n");
        let cp = extract(&lines);
        assert_eq!(cp.errors.len(), 8);
        assert_eq!(cp.errors[0], "Error 4");
        assert_eq!(cp.errors[7], "Error 11");
    }

    #[test]
    fn three_archived_results_yield_id_lines_on_restore() {
        let dir = std::env::temp_dir().join("rtok-t582-three-ids");
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(dir.join("t.jsonl"), FIXTURE).unwrap();
        let cfg = crate::testutil::config_in(&dir);
        let store = crate::store::Store::open(&cfg.core.db_path).unwrap();
        let mut ids = Vec::new();
        for (i, body) in [b"one-result".as_slice(), b"two-result", b"three-result"]
            .into_iter()
            .enumerate()
        {
            let id = store
                .put_archive("t582", body, &cfg.core.archive_dir)
                .unwrap();
            store
                .put_archive_decision(&format!("tu{i}"), &id, "t582", "ptr")
                .unwrap();
            ids.push(id);
        }
        ids.reverse(); // newest first
        let pre = serde_json::json!({
            "hook_event_name":"PreCompact",
            "session_id":"t582",
            "transcript_path":dir.join("t.jsonl").to_str().unwrap(),
            "trigger":"auto"
        });
        let mut out = Vec::new();
        crate::hooks::run("PreCompact", pre.to_string().as_bytes(), &mut out, &cfg);
        assert_eq!(out, b"{}");
        let body = crate::store::Store::open(&cfg.core.db_path)
            .unwrap()
            .latest_note("checkpoint:t582")
            .unwrap()
            .expect("note");
        for id in &ids {
            let line = format!("id {id} - {}", /* bytes filled below */ 0);
            let _ = line;
            assert!(
                body.lines().any(|l| l.starts_with(&format!("id {id} - "))),
                "missing {id} in {body}"
            );
        }
        assert_eq!(
            body.lines().filter(|l| l.starts_with("id ")).count(),
            3,
            "{body}"
        );
        let start = serde_json::json!({
            "hook_event_name":"SessionStart","session_id":"t582","source":"compact"
        });
        out.clear();
        crate::hooks::run("SessionStart", start.to_string().as_bytes(), &mut out, &cfg);
        let text = serde_json::from_slice::<serde_json::Value>(&out).unwrap()["hookSpecificOutput"]
            ["additionalContext"]
            .as_str()
            .unwrap_or("")
            .to_string();
        for id in &ids {
            assert!(text.contains(&format!("id {id} - ")), "{text}");
        }
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// T71.2 e2e: `SessionEnd` saves the transcript under `session:<id>` with the project
    /// from the hook cwd; `startup_recall` off injects nothing, on (the default) restores the
    /// newest project note byte-stably within `checkpoint_tokens`, measured as `handoff`.
    #[test]
    fn session_end_note_and_startup_recall() {
        let dir = std::env::temp_dir().join("rtok-t712-session-end");
        let _ = std::fs::remove_dir_all(&dir);
        let repo = dir.join("myproj");
        std::fs::create_dir_all(repo.join(".git")).unwrap();
        std::fs::write(repo.join("t.jsonl"), FIXTURE).unwrap();
        let mut cfg = crate::testutil::config_in(&dir);
        cfg.plugins.inject.modes.clear();
        let end = serde_json::json!({
            "hook_event_name":"SessionEnd",
            "session_id":"t712",
            "transcript_path":repo.join("t.jsonl").to_str().unwrap(),
            "cwd":repo.display().to_string(),
            "reason":"clear"
        });
        let mut out = Vec::new();
        crate::hooks::run("SessionEnd", end.to_string().as_bytes(), &mut out, &cfg);
        assert_eq!(out, b"{}");
        let body = crate::store::Store::open(&cfg.core.db_path)
            .unwrap()
            .latest_session_note(Some("myproj"))
            .unwrap()
            .expect("session note");
        assert!(body.starts_with("checkpoint\n"), "{body}");
        for p in ["src/a.rs", "src/b.rs", "src/c.rs"] {
            assert!(body.contains(p), "{body}");
        }
        // T419: the hook path stores the prompt counts as memory's plugin state.
        let cp = extract(FIXTURE);
        let state = crate::store::Store::open(&cfg.core.db_path)
            .unwrap()
            .kv_get(&crate::plugin::plugin_state_key("memory", "session:t712"))
            .unwrap()
            .expect("prompt counts row");
        assert_eq!(
            serde_json::from_str::<serde_json::Value>(&state).unwrap(),
            serde_json::json!({ "typed": cp.typed, "skipped": cp.skipped })
        );

        let start = serde_json::json!({
            "hook_event_name":"SessionStart",
            "session_id":"t712-next",
            "source":"startup",
            "cwd":repo.display().to_string()
        });
        cfg.plugins.memory.startup_recall = false;
        let mut off = Vec::new();
        crate::hooks::run("SessionStart", start.to_string().as_bytes(), &mut off, &cfg);
        // T283: SessionStart always offers this session's own rtok agent id; `startup_recall`
        // off means checkpoint itself contributes nothing beyond that one line.
        let off_ctx = serde_json::from_slice::<serde_json::Value>(&off).unwrap()
            ["hookSpecificOutput"]["additionalContext"]
            .as_str()
            .unwrap_or("")
            .to_string();
        assert!(off_ctx.starts_with("rtok agent id: "), "{off_ctx}");
        assert!(
            !off_ctx.contains("checkpoint"),
            "startup_recall off injects nothing from checkpoint: {off_ctx}"
        );

        cfg.plugins.memory.startup_recall = true;
        let mut on1 = Vec::new();
        crate::hooks::run("SessionStart", start.to_string().as_bytes(), &mut on1, &cfg);
        let mut on2 = Vec::new();
        crate::hooks::run("SessionStart", start.to_string().as_bytes(), &mut on2, &cfg);
        assert_eq!(on1, on2, "byte-stable for an unchanged store");
        let text = serde_json::from_slice::<serde_json::Value>(&on1).unwrap()["hookSpecificOutput"]
            ["additionalContext"]
            .as_str()
            .unwrap_or("")
            .to_string();
        for p in ["src/a.rs", "src/b.rs", "src/c.rs"] {
            assert!(text.contains(p), "{text}");
        }
        let est = crate::plugin::Runtime::in_memory("t712-budget").unwrap();
        assert!(
            est.estimate(&text, Class::Prose) <= est.config.plugins.memory.checkpoint_tokens,
            "{text}"
        );
        let handoffs = crate::store::Store::open(&cfg.core.db_path)
            .unwrap()
            .list_measurements("memory")
            .unwrap()
            .into_iter()
            .filter(|r| r.kind == "handoff")
            .count();
        assert_eq!(handoffs, 2, "one measurement per startup restore");
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// The cap is a hard ceiling whatever the text size, and the cut is not one pop per char.
    #[test]
    fn offer_fits_checkpoint_tokens() {
        let cx = crate::plugin::Runtime::in_memory("cap").unwrap();
        let ctx = Ctx::new(&cx);
        let cap = cx.config.plugins.memory.checkpoint_tokens.max(1);
        let big = "checkpoint\n".repeat(4000);
        ctx.upsert_note(Some("rtok"), &kind(&ctx), "compact", &big)
            .unwrap();
        let inj = offer(&ctx).expect("injection");
        assert!(cx.estimate(&inj.text, Class::Prose) <= cap);
        assert!(inj.text.starts_with("checkpoint\n"), "{}", inj.text);

        let mut many = Checkpoint {
            prompts: vec!["x".repeat(80)],
            ..Default::default()
        };
        for i in 0..200 {
            many.ids.push(format!("id{i:04}"));
            many.id_meta.push(("Read".into(), 9999));
            if cx.estimate(&many.render(), Class::Prose) > cap {
                many.ids.pop();
                many.id_meta.pop();
                break;
            }
        }
        assert!(!many.ids.is_empty());
        assert!(cx.estimate(&many.render(), Class::Prose) <= cap);
        ctx.upsert_note(Some("rtok"), &kind(&ctx), "compact", &many.render())
            .unwrap();
        let inj = offer(&ctx).expect("capped ids");
        assert!(cx.estimate(&inj.text, Class::Prose) <= cap);
    }

    /// T70.6: pi/OpenCode plugins call the same PreCompact/SessionStart path as Claude,
    /// so restore bytes match for the same store. T58.2 covers hook hosts only.
    #[test]
    fn pi_and_opencode_compact_restore_bytes_match_claude() {
        let dir = std::env::temp_dir().join("rtok-t706-host-bytes");
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(dir.join("t.jsonl"), FIXTURE).unwrap();
        let mut cfg = crate::testutil::config_in(&dir);
        cfg.plugins.inject.modes.clear();
        let pre = serde_json::json!({
            "hook_event_name":"PreCompact",
            "session_id":"t706",
            "transcript_path":dir.join("t.jsonl").to_str().unwrap(),
            "trigger":"auto"
        });
        let mut out = Vec::new();
        crate::hooks::run("PreCompact", pre.to_string().as_bytes(), &mut out, &cfg);
        let mut restore = |host: &str| {
            cfg.hook.host = host.into();
            let start = serde_json::json!({
                "hook_event_name":"SessionStart",
                "session_id":"t706",
                "source":"compact"
            });
            let mut buf = Vec::new();
            crate::hooks::run("SessionStart", start.to_string().as_bytes(), &mut buf, &cfg);
            serde_json::from_slice::<serde_json::Value>(&buf).unwrap()["hookSpecificOutput"]
                ["additionalContext"]
                .as_str()
                .unwrap_or("")
                .to_string()
        };
        let claude = restore("claude");
        let pi = restore("pi");
        let opencode = restore("opencode");
        assert!(!claude.is_empty(), "{claude}");
        // T283: each host registers its own rtok agent id under the same session_id text (a
        // fresh UUID per `(host, session)` row), so the leading `rtok agent id: ...` line
        // differs by design — the claim is about the checkpoint body that follows it.
        fn after_first_line(s: &str) -> &str {
            s.split_once('\n').map_or("", |(_, rest)| rest)
        }
        for text in [&claude, &pi, &opencode] {
            assert!(text.starts_with("rtok agent id: "), "{text}");
        }
        assert_eq!(after_first_line(&claude), after_first_line(&pi));
        assert_eq!(after_first_line(&claude), after_first_line(&opencode));
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// ~50 MB shaped like a real session: mostly `type":"user"` lines carrying a
    /// `tool_result` block (a large Bash/Read result the harness echoes back — the shape
    /// that dominates a real transcript's bytes, per T203 code review), a few large
    /// assistant text blocks, and a real prompt/path/error line planted every `PERIOD`
    /// lines so the extraction is exercised at scale and not just on filler.
    fn big_transcript(target_bytes: usize) -> String {
        const PERIOD: usize = 500;
        let tool_output = "line of bash output ".repeat(6);
        let prose = "lorem ipsum dolor sit amet consectetur adipiscing elit ".repeat(3);
        let mut s = String::with_capacity(target_bytes + 4096);
        let mut i = 0usize;
        while s.len() < target_bytes {
            if i.is_multiple_of(PERIOD) {
                for v in [
                    serde_json::json!({"type":"user","message":{"content":format!("prompt {i}")}}),
                    serde_json::json!({"type":"assistant","message":{"content":[{"type":"tool_use","name":"Read","input":{"file_path":format!("src/file{i}.rs")}}]}}),
                    serde_json::json!({"type":"user","message":{"content":[{"type":"text","text":format!("still failing with error: boom {i}")}]}}),
                ] {
                    s.push_str(&v.to_string());
                    s.push('\n');
                }
            } else if i.is_multiple_of(2) {
                // The dominant real-world shape (T203 review): a tool result, not typed
                // by anyone, echoed back as a `user` turn.
                let v = serde_json::json!({"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":format!("toolu_{i}"),"content":tool_output}]}});
                s.push_str(&v.to_string());
                s.push('\n');
            } else {
                let v = serde_json::json!({"type":"assistant","message":{"content":[{"type":"text","text":prose}]}});
                s.push_str(&v.to_string());
                s.push('\n');
            }
            i += 1;
        }
        s
    }

    /// T203: `checkpoint::write` used to `read_to_string` the whole transcript and
    /// `attach_ids`/`offer_session` each opened a second/third `Store` on the same SQLite
    /// file the hook `Runtime` already holds open. On a ~50 MB transcript this checks the
    /// streamed extractor stays fast, opens the store exactly once for the whole hook run,
    /// and yields the same note body [`extract`] (the in-memory extractor) computes for the
    /// same bytes.
    #[test]
    fn session_end_on_a_large_transcript_is_bounded() {
        let dir = std::env::temp_dir().join("rtok-t203-large-transcript");
        let _ = std::fs::remove_dir_all(&dir);
        let repo = dir.join("bigproj");
        std::fs::create_dir_all(repo.join(".git")).unwrap();
        let content = big_transcript(50 * 1024 * 1024);
        std::fs::write(repo.join("t.jsonl"), &content).unwrap();
        let expected = extract(&content);
        assert!(!expected.paths.is_empty() && !expected.errors.is_empty());

        let mut cfg = crate::testutil::config_in(&dir);
        cfg.plugins.inject.modes.clear();
        let end = serde_json::json!({
            "hook_event_name":"SessionEnd",
            "session_id":"t203",
            "transcript_path":repo.join("t.jsonl").to_str().unwrap(),
            "cwd":repo.display().to_string(),
            "reason":"clear"
        });
        let mut out = Vec::new();
        let before = crate::store::OPEN_COUNT.with(|n| n.get());
        let started = std::time::Instant::now();
        crate::hooks::run("SessionEnd", end.to_string().as_bytes(), &mut out, &cfg);
        let elapsed = started.elapsed();
        let after = crate::store::OPEN_COUNT.with(|n| n.get());
        assert_eq!(out, b"{}");
        assert_eq!(after - before, 1, "one Store::open per hook run");
        // Measured on this machine with this (tool_result-dominated) transcript: ~57 ms
        // `--release`, under the task's 100 ms bound; ~960 ms unoptimized `cargo test`,
        // where serde_json and every `str::contains` run unoptimized. Shared CI runners are
        // several times slower still, so the bound only catches a quadratic regression; the
        // open count above and the streaming read are what keep the hook bounded.
        assert!(
            elapsed.as_secs() < 10,
            "SessionEnd on a 50 MB transcript took {elapsed:?}"
        );

        let body = crate::store::Store::open(&cfg.core.db_path)
            .unwrap()
            .latest_session_note(Some("bigproj"))
            .unwrap()
            .expect("session note");
        assert_eq!(body, expected.render());
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// T203 code review: `worth_parsing`'s claim to be a superset (never skips a line a full
    /// parse would have found something in) is checked here, not just asserted in a
    /// comment — on every fixture already used in this module, and on the ~50 MB
    /// transcript above whose bulk is exactly the shape (`tool_result` blocks) the filter
    /// is supposed to skip.
    #[test]
    fn prefilter_is_a_true_superset_on_fixtures_and_a_large_transcript() {
        let skill_fixture = {
            let body = "Base directory for this skill: /home/u/.claude/skills/pixel\n\n# Pixel\n"
                .to_string()
                + &"x".repeat(5000);
            [
                r#"{"type":"user","message":{"content":"make it blue"}}"#.to_string(),
                serde_json::json!({"type":"user","isMeta":true,"sourceToolUseID":"toolu_1","message":{"content":[{"type":"text","text":body}]}}).to_string(),
                serde_json::json!({"type":"user","isMeta":true,"sourceToolUseID":"toolu_2","message":{"content":[{"type":"text","text":"Base directory for this skill: C:\\u\\.claude\\plugins\\cache\\p\\1.0\\skills\\ponytail\n\n# P"}]}}).to_string(),
                r#"{"type":"user","isMeta":true,"message":{"content":"Base directory for this skill: /x/y"}}"#.to_string(),
            ]
            .join("\n")
        };
        let big = big_transcript(2 * 1024 * 1024);
        for (name, content) in [
            ("FIXTURE", FIXTURE),
            ("HOST_FIXTURE", HOST_FIXTURE),
            ("skill fixture", &skill_fixture),
            ("2 MB transcript", &big),
        ] {
            let filtered = extract_lines_with(content.as_bytes().lines(), true);
            let unfiltered = extract_lines_with(content.as_bytes().lines(), false);
            assert_eq!(
                filtered, unfiltered,
                "prefilter dropped something on {name}"
            );
        }
    }
}
