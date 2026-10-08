// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! PreCompact checkpoint + compact restore (plan T2.5).

use rtok_plugin_sdk::{Class, Ctx, Injection, Measurement};
use serde_json::Value;
use std::collections::{BTreeMap, HashMap, VecDeque};
use std::io::BufRead;
use std::path::Path;

/// Parsed compact snapshot.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Checkpoint {
    pub prompts: Vec<String>,
    /// Changed files first (edited, created, deleted), then read ones, each group by path:
    /// the order is the render order and has to be byte-stable for the same transcript (T375).
    pub paths: Vec<PathEntry>,
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

/// What the session did to a file. The derived order is the render order: a file the
/// agent changed matters more after compaction than one it only looked at (T375).
#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub enum Action {
    Edited,
    Created,
    Deleted,
    Read,
}

impl Action {
    fn word(self) -> &'static str {
        match self {
            Self::Edited => "edited",
            Self::Created => "created",
            Self::Deleted => "deleted",
            Self::Read => "read",
        }
    }

    fn from_word(w: &str) -> Option<Self> {
        [Self::Edited, Self::Created, Self::Deleted, Self::Read]
            .into_iter()
            .find(|a| a.word() == w)
    }
}

/// `offset`, and `offset + limit - 1` when a limit was given, of the last ranged `Read`.
type Lines = (u64, Option<u64>);

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct PathEntry {
    pub path: String,
    pub action: Action,
    /// Only a read carries a range; any later change makes it meaningless.
    pub lines: Option<Lines>,
}

impl PathEntry {
    fn render(&self, out: &mut String) {
        out.push_str("path ");
        out.push_str(&self.path);
        out.push_str(" (");
        out.push_str(self.action.word());
        if let Some((from, to)) = self.lines {
            out.push_str(&format!(" {from}-"));
            if let Some(to) = to {
                out.push_str(&to.to_string());
            }
        }
        out.push_str(")\n");
    }

    /// The text after `path ` in a stored note. Rows written before T375 are a bare path,
    /// and a suffix that is not an action word belongs to the path: both decode as a read.
    fn parse(rest: &str) -> Self {
        let plain = || Self {
            path: rest.to_string(),
            action: Action::Read,
            lines: None,
        };
        let Some((path, tail)) = rest
            .strip_suffix(')')
            .and_then(|body| body.rsplit_once(" ("))
        else {
            return plain();
        };
        let (word, range) = tail
            .split_once(' ')
            .map_or((tail, None), |(w, r)| (w, Some(r)));
        let Some(action) = Action::from_word(word) else {
            return plain();
        };
        let lines = range.and_then(|r| {
            let (from, to) = r.split_once('-')?;
            Some((from.parse().ok()?, to.parse().ok()))
        });
        Self {
            path: path.to_string(),
            action,
            lines,
        }
    }
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
            p.render(&mut s);
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
    let mut files = Files::default();
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
        walk(&v, &mut files.seen, &mut |s| {
            // The three spellings that occur in compiler, test and runtime output; no
            // lowercased copy of every transcript string.
            if ["error", "Error", "ERROR"].iter().any(|n| s.contains(n)) {
                if errors.len() == 8 {
                    errors.pop_front();
                }
                errors.push_back(s.chars().take(200).collect());
            }
        });
        files.track(&v);
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
        paths: files.into_entries(),
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
    // T375: a `rm` in a Bash call names no `file_path`, and a Write result is what tells a
    // created file from an overwritten one.
    if line.contains("\"type\":\"create\"")
        || line.contains("File created successfully")
        || (line.contains("\"name\":\"Bash\"") && line.contains("rm"))
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

/// What the session did to one path so far, and the range of its last ranged read.
type Slot = (Action, Option<Lines>);

/// Per-path actions folded from a transcript's `tool_use` blocks (T375). The transcript, not
/// a PostToolUse event, is the source because the checkpoint is written at PreCompact and
/// SessionEnd, from the file the host keeps.
#[derive(Default)]
struct Files {
    /// Every `file_path` seen anywhere stays a read unless a tool call says otherwise, as
    /// before T375.
    seen: BTreeMap<String, Slot>,
    /// `Write` calls still waiting for their result: only the result says whether the file
    /// was new.
    writes: HashMap<String, String>,
}

impl Files {
    fn track(&mut self, v: &Value) {
        let Some(blocks) = v.pointer("/message/content").and_then(Value::as_array) else {
            return;
        };
        let cwd = v.get("cwd").and_then(Value::as_str);
        // Claude Code puts `toolUseResult` beside one result; with several results in a
        // record it cannot be told which one it describes.
        let created = blocks.len() == 1
            && v.pointer("/toolUseResult/type").and_then(Value::as_str) == Some("create");
        for b in blocks {
            match b.get("type").and_then(Value::as_str) {
                Some("tool_use") => self.tool_use(b, cwd),
                Some("tool_result") => self.tool_result(b, created),
                _ => {}
            }
        }
    }

    fn tool_use(&mut self, b: &Value, cwd: Option<&str>) {
        let input = b.get("input");
        let path = input
            .and_then(|i| i.get("file_path"))
            .and_then(Value::as_str)
            .filter(|p| !p.is_empty());
        match (b.get("name").and_then(Value::as_str), path) {
            (Some("Read"), Some(p)) => {
                let n = |k: &str| input.and_then(|i| i.get(k)).and_then(Value::as_u64);
                let from = n("offset");
                let lines = (from.is_some() || n("limit").is_some()).then(|| {
                    let from = from.unwrap_or(1);
                    (
                        from,
                        n("limit").map(|l| from.saturating_add(l.saturating_sub(1))),
                    )
                });
                let slot = self.slot(p);
                if slot.0 == Action::Read {
                    slot.1 = lines;
                }
            }
            (Some("Edit" | "MultiEdit"), Some(p)) => self.mark(p, Action::Edited),
            // An overwrite until the result proves the file was new.
            (Some("Write"), Some(p)) => {
                self.mark(p, Action::Edited);
                if let Some(id) = b.get("id").and_then(Value::as_str) {
                    self.writes.insert(id.to_string(), p.to_string());
                }
            }
            (Some("Bash"), _) => {
                let cmd = input.and_then(|i| i.get("command")).and_then(Value::as_str);
                for p in cmd.map(|c| removed_paths(c, cwd)).unwrap_or_default() {
                    self.mark(&p, Action::Deleted);
                }
            }
            _ => {}
        }
    }

    fn tool_result(&mut self, b: &Value, created: bool) {
        let Some(path) = b
            .get("tool_use_id")
            .and_then(Value::as_str)
            .and_then(|id| self.writes.remove(id))
        else {
            return;
        };
        let said = match b.get("content") {
            Some(Value::String(s)) => s.starts_with("File created successfully"),
            Some(Value::Array(a)) => a.iter().any(|c| {
                c.get("text")
                    .and_then(Value::as_str)
                    .is_some_and(|t| t.starts_with("File created successfully"))
            }),
            _ => false,
        };
        if created || said {
            self.mark(&path, Action::Created);
        }
    }

    fn slot(&mut self, path: &str) -> &mut Slot {
        self.seen
            .entry(path.to_string())
            .or_insert((Action::Read, None))
    }

    /// A file the session created stays created through later edits; any other change
    /// replaces what was known, and a change drops the read range.
    fn mark(&mut self, path: &str, action: Action) {
        let slot = self.slot(path);
        if !(slot.0 == Action::Created && action == Action::Edited) {
            slot.0 = action;
        }
        slot.1 = None;
    }

    fn into_entries(self) -> Vec<PathEntry> {
        let mut v: Vec<PathEntry> = self
            .seen
            .into_iter()
            .map(|(path, (action, lines))| PathEntry {
                path,
                action,
                lines,
            })
            .collect();
        // Stable, so each group keeps the path order of the map.
        v.sort_by_key(|e| e.action);
        v
    }
}

/// Paths a shell command removes with `rm` or `git rm`, relative ones resolved against
/// `cwd`. Conservative on purpose: a word with a glob, a variable or a `~` names no known
/// file, `git rm --cached` keeps the file on disk, and a command that does not parse
/// yields nothing.
fn removed_paths(command: &str, cwd: Option<&str>) -> Vec<String> {
    let Some(words) = shlex::split(command) else {
        return Vec::new();
    };
    let moved = words.iter().any(|w| matches!(w.as_str(), "cd" | "pushd"));
    let mut out = Vec::new();
    let mut i = 0;
    let mut start = true;
    while i < words.len() {
        // `;` glued to the last word ends the command just as a word of its own does.
        let (w, ends) = match words[i].strip_suffix(';') {
            Some(w) => (w, true),
            None => (
                words[i].as_str(),
                matches!(words[i].as_str(), "&&" | "||" | "|" | "&"),
            ),
        };
        let git_rm = start && w == "git" && words.get(i + 1).is_some_and(|n| n == "rm");
        if start && (w == "rm" || git_rm) && !ends {
            i += if git_rm { 2 } else { 1 };
            let (mut files, mut keeps) = (Vec::new(), false);
            let mut flags = true;
            while let Some(a) = words.get(i) {
                let (a, last) = match a.strip_suffix(';') {
                    Some(a) => (a, true),
                    None => (a.as_str(), false),
                };
                if matches!(a, "&&" | "||" | "|" | "&") {
                    break;
                }
                if flags && a == "--" {
                    flags = false;
                } else if flags && a.starts_with('-') {
                    keeps |= git_rm && matches!(a, "--cached" | "-n" | "--dry-run");
                } else if !a.is_empty() && !a.contains(['*', '?', '[', '{', '$', '`', '~', '!']) {
                    files.push(a);
                }
                i += 1;
                if last {
                    break;
                }
            }
            if !keeps {
                out.extend(
                    files
                        .into_iter()
                        // After a `cd` the record's cwd is no longer where a relative path points.
                        .filter(|f| !moved || Path::new(f).is_absolute())
                        .map(|f| resolve(cwd, f)),
                );
            }
            start = true;
            continue;
        }
        start = ends;
        i += 1;
    }
    out
}

/// `path` made absolute against `cwd` lexically, so the `rm` of a file the session read by
/// its absolute path lands on the same entry. Bash paths are POSIX even on Windows (Git
/// Bash), where `Path` would not see `/abs` as absolute and would join with `\`, so the
/// split is by hand and the result keeps `cwd`'s own separator.
fn resolve(cwd: Option<&str>, path: &str) -> String {
    let absolute = path.starts_with('/') || Path::new(path).is_absolute();
    let Some(cwd) = cwd.filter(|_| !absolute) else {
        return path.to_string();
    };
    let sep = if cwd.contains('/') { "/" } else { "\\" };
    let mut out: Vec<&str> = cwd
        .trim_end_matches(['/', '\\'])
        .split(['/', '\\'])
        .collect();
    for c in path.split('/') {
        match c {
            "" | "." => {}
            ".." => {
                if out.len() > 1 {
                    out.pop();
                }
            }
            n => out.push(n),
        }
    }
    out.join(sep)
}

fn walk(v: &Value, paths: &mut BTreeMap<String, Slot>, text: &mut impl FnMut(&str)) {
    match v {
        Value::String(s) => text(s),
        Value::Object(m) => {
            if let Some(p) = m.get("file_path").and_then(Value::as_str)
                && !p.is_empty()
            {
                paths.entry(p.to_string()).or_insert((Action::Read, None));
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

/// T370: the files the session's last checkpoint named, relative to `root` where they sit
/// under it. Personalizes the repo map after a compaction; any read failure is an empty list.
pub fn last_paths(cx: &Ctx, root: &str) -> Vec<String> {
    let Some(body) = cx.latest_note(&kind(cx)).ok().flatten() else {
        return Vec::new();
    };
    let prefix = format!("{}/", root.trim_end_matches('/'));
    body.lines()
        .filter_map(|l| l.strip_prefix("path "))
        .map(PathEntry::parse)
        // A deleted file has nothing left to personalize the map with.
        .filter(|e| e.action != Action::Deleted)
        .map(|e| e.path.strip_prefix(&prefix).unwrap_or(&e.path).to_string())
        .collect()
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
    fn entry(path: &str, action: Action) -> PathEntry {
        PathEntry {
            path: path.into(),
            action,
            lines: None,
        }
    }

    fn paths_of(cp: &Checkpoint) -> Vec<&str> {
        cp.paths.iter().map(|e| e.path.as_str()).collect()
    }

    const FIXTURE: &str = r#"{"type":"user","message":{"role":"user","content":"edit the three files"}}
{"type":"assistant","message":{"content":[{"type":"tool_use","name":"Read","input":{"file_path":"src/a.rs"}},{"type":"tool_use","name":"Read","input":{"file_path":"src/b.rs"}},{"type":"tool_use","name":"Read","input":{"file_path":"src/c.rs"}}]}}
{"type":"user","message":{"role":"user","content":[{"type":"text","text":"still failing with error: boom"}]}}
"#;

    /// T370: the repo map is personalized by the paths the session's own checkpoint named,
    /// made relative to the root where they sit under it.
    #[test]
    fn last_paths_are_root_relative_and_empty_without_a_checkpoint() {
        let (rt, dir) = crate::testutil::runtime("last-paths");
        let ctx = Ctx::new(&rt);
        assert!(last_paths(&ctx, "/r").is_empty());
        let cp = Checkpoint {
            paths: ["/r/src/a.rs", "docs/b.md", "/else/c.rs"]
                .map(|p| entry(p, Action::Read))
                .into(),
            ..Checkpoint::default()
        };
        ctx.upsert_note(None, &kind(&ctx), "compact", &cp.render())
            .unwrap();
        assert_eq!(
            last_paths(&ctx, "/r"),
            ["src/a.rs", "docs/b.md", "/else/c.rs"]
        );
        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn fixture_has_three_paths_and_compact_injects_under_budget() {
        let cp = extract(FIXTURE);
        assert_eq!(paths_of(&cp), ["src/a.rs", "src/b.rs", "src/c.rs"]);
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

    /// T375: one record per tool call shape the action mapping reads, in the compact JSON
    /// Claude Code writes (checked against a live transcript, 2026-10-08).
    const ACTIONS: &str = r#"{"type":"user","message":{"role":"user","content":"fix a, rewrite b"}}
{"type":"assistant","cwd":"/r","message":{"content":[{"type":"tool_use","id":"t1","name":"Read","input":{"file_path":"/r/src/a.rs","offset":10,"limit":50}},{"type":"tool_use","id":"t2","name":"Read","input":{"file_path":"/r/src/b.rs"}}]}}
{"type":"assistant","cwd":"/r","message":{"content":[{"type":"tool_use","id":"t3","name":"Edit","input":{"file_path":"/r/src/b.rs","old_string":"x","new_string":"y"}}]}}
{"type":"assistant","cwd":"/r","message":{"content":[{"type":"tool_use","id":"t4","name":"Write","input":{"file_path":"/r/src/new.rs","content":"z"}},{"type":"tool_use","id":"t5","name":"Write","input":{"file_path":"/r/src/old.rs","content":"z"}}]}}
{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t4","content":"File created successfully at: /r/src/new.rs"}]},"toolUseResult":{"type":"create","filePath":"/r/src/new.rs"}}
{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t5","content":"The file /r/src/old.rs has been updated successfully."}]},"toolUseResult":{"filePath":"/r/src/old.rs"}}
{"type":"assistant","cwd":"/r","message":{"content":[{"type":"tool_use","id":"t6","name":"Bash","input":{"command":"rm -f src/gone.rs && git rm src/c.rs"}}]}}
"#;

    /// T375 done-when: a session that reads A and edits B lists B before A, and the rest of
    /// the actions land in their groups with the last ranged read's lines.
    #[test]
    fn changed_files_are_listed_before_read_ones() {
        let cp = extract(ACTIONS);
        insta::assert_snapshot!(cp.render(), @"
        checkpoint
        - fix a, rewrite b
        path /r/src/b.rs (edited)
        path /r/src/old.rs (edited)
        path /r/src/new.rs (created)
        path /r/src/c.rs (deleted)
        path /r/src/gone.rs (deleted)
        path /r/src/a.rs (read 10-59)
        ");
        assert_eq!(cp.render(), extract(ACTIONS).render(), "byte-stable");
    }

    fn actions_of(jsonl: &str) -> Vec<(String, Action)> {
        extract(jsonl)
            .paths
            .into_iter()
            .map(|e| (e.path, e.action))
            .collect()
    }

    fn tool(name: &str, input: serde_json::Value) -> String {
        serde_json::json!({"type":"assistant","cwd":"/r","message":{"content":[{"type":"tool_use","id":"t","name":name,"input":input}]}}).to_string()
    }

    #[test]
    fn tool_names_map_to_actions_and_a_change_is_never_downgraded() {
        let p = |n: &str| serde_json::json!({ "file_path": format!("/r/{n}") });
        let lines = [
            tool("Read", p("read")),
            tool("Grep", p("grep")),
            tool("Edit", p("edit")),
            tool("MultiEdit", p("multi")),
            // Read after Edit keeps the edit; Edit after a create keeps the create.
            tool("Read", p("edit")),
            tool("Write", p("made")),
            tool("Edit", p("made")),
            // A later delete wins, and a later read of a deleted file does not undo it.
            tool("Edit", p("gone")),
            tool("Bash", serde_json::json!({"command":"rm gone"})),
            tool("Read", p("gone")),
        ]
        .join("\n");
        let got = actions_of(&lines);
        let want = |n: &str| got.iter().find(|(p, _)| p == &format!("/r/{n}")).unwrap().1;
        assert_eq!(want("read"), Action::Read);
        assert_eq!(want("grep"), Action::Read, "an unknown tool stays a read");
        assert_eq!(want("edit"), Action::Edited);
        assert_eq!(want("multi"), Action::Edited);
        assert_eq!(want("gone"), Action::Deleted);
        // Without a result a Write cannot be told from an overwrite: edited, not created.
        assert_eq!(want("made"), Action::Edited);
    }

    #[test]
    fn a_write_is_created_only_when_its_result_says_so() {
        let write = |id: &str, p: &str| {
            serde_json::json!({"type":"assistant","message":{"content":[{"type":"tool_use","id":id,"name":"Write","input":{"file_path":p}}]}}).to_string()
        };
        let result = |id: &str, content: serde_json::Value| {
            serde_json::json!({"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":id,"content":content}]}}).to_string()
        };
        let lines = [
            write("a", "/r/by-text"),
            result("a", "File created successfully at: /r/by-text".into()),
            write("b", "/r/by-blocks"),
            result(
                "b",
                serde_json::json!([{"type":"text","text":"File created successfully at: x"}]),
            ),
            write("c", "/r/overwritten"),
            result(
                "c",
                "The file /r/overwritten has been updated successfully.".into(),
            ),
            // A forged id names no pending Write.
            result("zzz", "File created successfully at: /r/forged".into()),
        ]
        .join("\n");
        let mut got = actions_of(&lines);
        got.sort();
        assert_eq!(
            got,
            [
                ("/r/by-blocks".to_string(), Action::Created),
                ("/r/by-text".to_string(), Action::Created),
                ("/r/overwritten".to_string(), Action::Edited),
            ]
        );
    }

    #[test]
    fn read_ranges_follow_the_last_ranged_read() {
        let read = |extra: serde_json::Value| {
            let mut i = serde_json::json!({"file_path":"/r/a"});
            i.as_object_mut()
                .unwrap()
                .extend(extra.as_object().unwrap().clone());
            tool("Read", i)
        };
        let lines = |l: &[String]| extract(&l.join("\n")).paths[0].lines;
        assert_eq!(
            lines(&[read(serde_json::json!({"offset":5,"limit":3}))]),
            Some((5, Some(7)))
        );
        assert_eq!(
            lines(&[read(serde_json::json!({"limit":4}))]),
            Some((1, Some(4)))
        );
        assert_eq!(
            lines(&[read(serde_json::json!({"offset":9}))]),
            Some((9, None))
        );
        assert_eq!(
            lines(&[
                read(serde_json::json!({"offset":5})),
                read(serde_json::json!({}))
            ]),
            None,
            "a whole-file read supersedes the range"
        );
    }

    #[test]
    fn bash_removals_are_read_conservatively() {
        let rm = |c: &str| removed_paths(c, Some("/r/sub"));
        assert_eq!(
            rm("rm a.rs ../b.rs /abs/c.rs"),
            ["/r/sub/a.rs", "/r/b.rs", "/abs/c.rs"]
        );
        assert_eq!(rm("rm -rf -- -odd dir/"), ["/r/sub/-odd", "/r/sub/dir"]);
        assert_eq!(
            rm("echo hi; rm a.rs; git rm -f b.rs && ls c.rs"),
            ["/r/sub/a.rs", "/r/sub/b.rs"]
        );
        assert_eq!(
            rm("cd x && rm a.rs /abs/b.rs"),
            ["/abs/b.rs"],
            "after a cd only absolute paths are known"
        );
        assert!(rm("rm *.rs").is_empty() && rm("rm $F ~/x").is_empty());
        assert!(
            rm("git rm --cached a.rs").is_empty(),
            "the file stays on disk"
        );
        assert!(rm("git rm -n a.rs").is_empty() && rm("echo rm a.rs").is_empty());
        assert!(rm("rm 'unterminated").is_empty() && rm("warm a.rs").is_empty());
        assert_eq!(removed_paths("rm a.rs", None), ["a.rs"]);
    }

    /// T375: rows written before the action existed are a bare path; they and a name that
    /// merely ends in parentheses decode as a read.
    #[test]
    fn old_rows_decode_as_reads() {
        for (row, path, action, lines) in [
            ("src/a.rs", "src/a.rs", Action::Read, None),
            ("src/a (copy).rs", "src/a (copy).rs", Action::Read, None),
            ("src/a (note)", "src/a (note)", Action::Read, None),
            ("src/a.rs (edited)", "src/a.rs", Action::Edited, None),
            ("a (b) (created)", "a (b)", Action::Created, None),
            ("a.rs (deleted)", "a.rs", Action::Deleted, None),
            ("a.rs (read)", "a.rs", Action::Read, None),
            (
                "a.rs (read 10-59)",
                "a.rs",
                Action::Read,
                Some((10, Some(59))),
            ),
            ("a.rs (read 10-)", "a.rs", Action::Read, Some((10, None))),
            ("a.rs (read x-y)", "a.rs", Action::Read, None),
        ] {
            let e = PathEntry::parse(row);
            assert_eq!(
                (e.path.as_str(), e.action, e.lines),
                (path, action, lines),
                "{row}"
            );
        }
        let (rt, dir) = crate::testutil::runtime("t375-old-rows");
        let ctx = Ctx::new(&rt);
        let old = "checkpoint\n- go\npath /r/src/a.rs\npath /r/src/b.rs (edited)\npath /r/src/c.rs (deleted)\npath docs/d.md (read 3-9)\n";
        ctx.upsert_note(None, &kind(&ctx), "compact", old).unwrap();
        assert_eq!(
            last_paths(&ctx, "/r"),
            ["src/a.rs", "src/b.rs", "docs/d.md"]
        );
        let _ = std::fs::remove_dir_all(dir);
    }

    /// T375: the change lines the prefilter must keep are the same ones the full parse reads.
    #[test]
    fn prefilter_keeps_the_action_lines() {
        for line in ACTIONS.lines().skip(1) {
            // An overwrite's result tells nothing the Write call did not: skipping it is the
            // point, and the superset test shows the outcome is the same.
            let update = line.contains("has been updated");
            assert_eq!(worth_parsing(line), !update, "{line}");
        }
        let skipped = r#"{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t","content":"ls output"}]}}"#;
        assert!(!worth_parsing(skipped));
        assert!(!worth_parsing(
            r#"{"type":"assistant","message":{"content":[{"type":"tool_use","name":"Bash","input":{"command":"ls"}}]}}"#
        ));
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

    /// Runs SessionEnd on a fresh ~`bytes` transcript in its own project under `dir` and
    /// returns the transcript and the hook's wall time, after checking the hook opened the
    /// store exactly once.
    fn timed_session_end(
        cfg: &crate::config::Config,
        dir: &Path,
        project: &str,
        bytes: usize,
    ) -> (String, std::time::Duration) {
        let repo = dir.join(project);
        std::fs::create_dir_all(repo.join(".git")).unwrap();
        let content = big_transcript(bytes);
        std::fs::write(repo.join("t.jsonl"), &content).unwrap();
        let end = serde_json::json!({
            "hook_event_name":"SessionEnd",
            "session_id":project,
            "transcript_path":repo.join("t.jsonl").to_str().unwrap(),
            "cwd":repo.display().to_string(),
            "reason":"clear"
        });
        let mut out = Vec::new();
        let before = crate::store::OPEN_COUNT.with(|n| n.get());
        let started = std::time::Instant::now();
        crate::hooks::run("SessionEnd", end.to_string().as_bytes(), &mut out, cfg);
        let elapsed = started.elapsed();
        let after = crate::store::OPEN_COUNT.with(|n| n.get());
        assert_eq!(out, b"{}");
        assert_eq!(after - before, 1, "one Store::open per hook run");
        (content, elapsed)
    }

    /// T203: `checkpoint::write` used to `read_to_string` the whole transcript and
    /// `attach_ids`/`offer_session` each opened a second/third `Store` on the same SQLite
    /// file the hook `Runtime` already holds open. On a ~50 MB transcript this checks the
    /// streamed extractor scales linearly, opens the store exactly once for the whole hook
    /// run, and yields the same note body [`extract`] (the in-memory extractor) computes for
    /// the same bytes.
    #[test]
    fn session_end_on_a_large_transcript_is_bounded() {
        let dir = std::env::temp_dir().join("rtok-t203-large-transcript");
        let _ = std::fs::remove_dir_all(&dir);
        let mut cfg = crate::testutil::config_in(&dir);
        cfg.plugins.inject.modes.clear();
        // T425: a fixed wall-clock bound (10 s) failed at 13.8 s on a debug build with host
        // load ~130, while the same run alone took 5.2-8.0 s. Only a quadratic regression is
        // worth catching here, so the 50 MB run is bounded by a 5 MB run timed in the same
        // test: linear work is at most ~10x (9.3x worst of ten runs beside 32 `yes` burners,
        // host load up to ~340), a per-line scan over every earlier line 90x. The small run
        // goes before and after the large one and the slower of the two is the baseline, so
        // load that rises or falls during the test inflates both sides of the ratio.
        let (_, small_before) = timed_session_end(&cfg, &dir, "smallproj1", 5 * 1024 * 1024);
        let (content, large) = timed_session_end(&cfg, &dir, "bigproj", 50 * 1024 * 1024);
        let (_, small_after) = timed_session_end(&cfg, &dir, "smallproj2", 5 * 1024 * 1024);
        let small = small_before.max(small_after);
        assert!(
            large < small * 30,
            "SessionEnd on 50 MB took {large:?}, over 30x the {small:?} on 5 MB \
             (before {small_before:?}, after {small_after:?}): extraction is not linear"
        );

        let expected = extract(&content);
        assert!(!expected.paths.is_empty() && !expected.errors.is_empty());
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
            ("ACTIONS", ACTIONS),
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
