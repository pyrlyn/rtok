// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! Notes API: `mem_save` / `mem_search` / `mem_get` (plan T6.1).

pub mod export;
mod files;
pub mod handoff;
pub mod import;
pub mod pack;
pub mod status;
pub mod sync;

pub use crate::project::project_name;

use rtok_plugin_sdk::{
    Class, Ctx, DashboardPage, Injection, Manifest, Measurement, Plugin, PromptSubmit,
    SessionStart, SubagentStart, Surface, ToolDef,
};
use serde_json::json;

/// One line in the hook index: titles only; names the MCP tools that fetch bodies (T293).
const INDEX_GUIDE: &str = "titles only; mem_search this turn; mem_get matching id for body";

pub struct Memory;

impl Plugin for Memory {
    fn manifest(&self) -> Manifest {
        Manifest {
            id: "memory",
            surfaces: &[Surface::Mcp, Surface::Hook],
            default_on: true,
        }
    }

    fn dashboard_page(&self) -> DashboardPage {
        DashboardPage::new(
            "Memory",
            "Recall notes and titles without an LLM extraction step.",
            true,
        )
    }

    fn mcp_tools(&self) -> Vec<ToolDef> {
        vec![
            ToolDef {
                name: "mem_save",
                description: "Save a note; same project+kind+title updates it.",
                // `kind` is not in `required`: the handler defaults it to "note" (a real,
                // intentional default), unlike `title`/`body`, which the handler used to
                // coerce to "" on omission — silently storing a broken note instead of
                // rejecting the call (T213).
                input_schema: json!({"type":"object","properties":{"kind":{"type":"string"},"title":{"type":"string"},"body":{"type":"string"},"project":{"type":"string"}},"required":["title","body"]}),
            },
            ToolDef {
                name: "mem_search",
                description: "Search notes by FTS5; ids, titles, snippets.",
                input_schema: json!({"type":"object","properties":{"query":{"type":"string"},"limit":{"type":"integer"}},"required":["query"]}),
            },
            ToolDef {
                name: "mem_pack",
                description: "Ranked notes in a token budget.",
                input_schema: json!({"type":"object","properties":{"query":{"type":"string"},"limit":{"type":"integer"},"max_tokens":{"type":"integer"}},"required":["query"]}),
            },
            ToolDef {
                name: "mem_get",
                description: "Body by note id; see hook index.",
                input_schema: json!({"type":"object","properties":{"id":{"type":"integer"}},"required":["id"]}),
            },
            ToolDef {
                name: "mem_update",
                description: "Retire or pin a note by id.",
                input_schema: json!({"type":"object","properties":{"id":{"type":"integer"},"retire":{"type":"boolean"},"superseded_by":{"type":"integer"},"pinned":{"type":"boolean"}},"required":["id"]}),
            },
            handoff::handoff_tool(),
        ]
    }

    fn session_start(&self, _ev: &SessionStart, cx: &Ctx) -> Option<Injection> {
        recall(cx)
    }

    fn prompt_submit(&self, ev: &PromptSubmit, cx: &Ctx) -> Option<Injection> {
        if let Some(inj) = remember_save(ev, cx) {
            return Some(inj);
        }
        prompt_recall(ev, cx)
    }

    fn subagent_start(&self, ev: &SubagentStart, cx: &Ctx) -> Option<Injection> {
        let cfg = cx.plugin_config::<crate::config::Memory>("memory");
        if !cfg.spawn_brief {
            return None;
        }
        let text = handoff::build_brief(cx, cfg.spawn_brief_tokens, ev.task_description)?;
        Some(Injection {
            plugin: "memory",
            text,
            priority: 9,
        })
    }
}

fn resolved_project(cx: &Ctx) -> Option<String> {
    cx.cwd()
        .map(std::path::Path::new)
        .and_then(project_name)
        .or_else(|| std::env::current_dir().ok().and_then(|d| project_name(&d)))
}

fn project_label(project: Option<&str>) -> &str {
    project.unwrap_or("none")
}

fn empty_project_line(project: Option<&str>) -> String {
    format!("memory project {}: no notes", project_label(project))
}

fn title_line(id: i32, title: &str, body_tokens: u32) -> String {
    format!("{id} {title} ({body_tokens}t body)")
}

/// Build capped index text from `(id, title, body_token_estimate)` rows, dropping newest last.
/// A lone entry that still overflows has its title halved, then is dropped: the cap holds.
fn render_title_index(cx: &Ctx, entries: &mut Vec<(i32, String, u32)>, cap: u32) -> String {
    loop {
        let mut lines = vec![INDEX_GUIDE.to_string()];
        for (id, title, tok) in entries.iter() {
            lines.push(title_line(*id, title, *tok));
        }
        let text = lines.join("\n");
        if cx.estimate(&text, Class::Prose) <= cap {
            return text;
        }
        if entries.len() > 1 {
            entries.pop();
        } else if let Some(entry) = entries.first_mut().filter(|e| !e.1.is_empty()) {
            let keep = entry.1.chars().count() / 2;
            entry.1 = entry.1.chars().take(keep).collect();
        } else {
            entries.clear();
            return INDEX_GUIDE.to_string();
        }
    }
}

/// Byte length and token estimate of each note's body, one read per note. The title line
/// shows the estimate and the measurement sums both, and a body is the heaviest thing the
/// SessionStart hook reads, so the second read per note is not repeated. A note whose body
/// is gone is absent.
fn body_sizes(
    cx: &Ctx,
    ids: impl Iterator<Item = i32>,
) -> std::collections::HashMap<i32, (u64, u32)> {
    ids.filter_map(|id| {
        let body = cx.get_note_body(id).ok().flatten()?;
        Some((id, (body.len() as u64, cx.estimate(&body, Class::Prose))))
    })
    .collect()
}

fn body_tokens(sizes: &std::collections::HashMap<i32, (u64, u32)>, id: i32) -> u32 {
    sizes.get(&id).map_or(0, |s| s.1)
}

/// The `(before_bytes, est_before)` a recall replaced: the bodies of the notes it kept.
fn bodies_before(
    sizes: &std::collections::HashMap<i32, (u64, u32)>,
    entries: &[(i32, String, u32)],
) -> (u64, u32) {
    entries
        .iter()
        .filter_map(|(id, _, _)| sizes.get(id))
        .fold((0, 0), |(bytes, est), (b, t)| {
            (bytes + b, est.saturating_add(*t))
        })
}

fn remember_save(ev: &PromptSubmit, cx: &Ctx) -> Option<Injection> {
    let first = ev.prompt.lines().next()?.trim();
    let rest = first.strip_prefix("remember:")?.trim();
    if rest.is_empty() {
        return None;
    }
    let title: String = rest.chars().take(80).collect();
    let project = cx.cwd().map(std::path::Path::new).and_then(project_name);
    let id = cx
        .upsert_note(project.as_deref(), "user", &title, rest)
        .ok()?;
    files::link_note(cx, id, rest);
    Some(Injection {
        plugin: "memory",
        text: format!("saved note {id}"),
        priority: 12,
    })
}

fn prompt_recall(ev: &PromptSubmit, cx: &Ctx) -> Option<Injection> {
    let cfg = cx.plugin_config::<crate::config::Memory>("memory");
    let n = cfg.prompt_recall;
    let cap = cfg.recall_tokens.max(1);
    if n == 0 {
        return None;
    }
    let query = ev
        .prompt
        .split_whitespace()
        .take(24)
        .collect::<Vec<_>>()
        .join(" ");
    if query.is_empty() {
        return None;
    }
    // T374: twice as many text hits as shown, so a note ranked just below the cut can still be
    // lifted by its file link; with no linked note the first `n` come back unchanged.
    let text_hits = cx.search_notes(&query, n.saturating_mul(2)).ok()?;
    let hits = files::recall_hits(cx, ev.prompt, text_hits, n as usize);
    if hits.is_empty() {
        return None;
    }
    let sizes = body_sizes(cx, hits.iter().map(|h| h.id));
    let mut entries: Vec<(i32, String, u32)> = hits
        .iter()
        .map(|h| (h.id, h.title.clone(), body_tokens(&sizes, h.id)))
        .collect();
    let text = render_title_index(cx, &mut entries, cap);
    let sha = crate::store::hex_sha256(text.as_bytes());
    if cx.last_measurement_ref("memory", "prompt_recall").ok()? == Some(sha.clone()) {
        return None;
    }
    let (before_bytes, est_before) = bodies_before(&sizes, &entries);
    let after_bytes = text.len() as u64;
    let est_after = cx.estimate(&text, Class::Prose);
    let _ = cx.record(&Measurement {
        plugin: "memory",
        kind: "prompt_recall",
        before_bytes,
        after_bytes,
        est_before,
        est_after,
        ref_id: Some(sha),
        call_id: None,
    });
    Some(Injection {
        plugin: "memory",
        text,
        priority: 11,
    })
}

fn recall(cx: &Ctx) -> Option<Injection> {
    let cfg = cx.plugin_config::<crate::config::Memory>("memory");
    let n = cfg.recall_titles.max(1);
    let cap = cfg.recall_tokens.max(1);
    let project = resolved_project(cx);
    let rows = cx.list_note_titles(project.as_deref(), n).ok()?;
    let mut kept: Vec<(i32, String, u32)> = Vec::new();
    let mut sizes = std::collections::HashMap::new();
    let text = if rows.is_empty() {
        let line = empty_project_line(project.as_deref());
        if cx.estimate(&line, Class::Prose) > cap {
            return None;
        }
        line
    } else {
        sizes = body_sizes(cx, rows.iter().map(|r| r.0));
        kept = rows
            .into_iter()
            .map(|(id, title)| (id, title, body_tokens(&sizes, id)))
            .collect();
        render_title_index(cx, &mut kept, cap)
    };
    let (before_bytes, est_before) = bodies_before(&sizes, &kept);
    let after_bytes = text.len() as u64;
    let est_after = cx.estimate(&text, Class::Prose);
    let _ = cx.record(&Measurement {
        plugin: "memory",
        kind: "recall",
        before_bytes,
        after_bytes,
        est_before,
        est_after,
        ref_id: Some(cx.session().to_string()),
        call_id: None,
    });
    Some(Injection {
        plugin: "memory",
        text,
        priority: 10,
    })
}

pub fn mem_save(
    rt: &crate::plugin::Runtime,
    kind: &str,
    title: &str,
    body: &str,
    project: Option<&str>,
) -> anyhow::Result<(i32, bool)> {
    let proj = project
        .map(str::to_string)
        .or_else(|| std::env::current_dir().ok().and_then(|d| project_name(&d)));
    // The title is the topic key (T66.1): a re-save updates the row, recall never shows
    // a stale twin next to the new one.
    let (id, updated) = rt.store.upsert_note(proj.as_deref(), kind, title, body)?;
    files::link_note(&Ctx::new(rt), id, body);
    rt.store
        .upsert_note_embedding(id, title, body, &rt.config.plugins.memory.embed)?;
    Ok((id, updated))
}

pub fn mem_pack(
    rt: &crate::plugin::Runtime,
    query: &str,
    limit: u32,
    max_tokens: u32,
) -> anyhow::Result<String> {
    let limit = limit.clamp(1, 20);
    let max_tokens = max_tokens.clamp(1, 2000);
    let hits = mem_search(rt, query, limit)?;
    let rows: Vec<(i32, String, String)> = hits
        .into_iter()
        .map(|h| (h.id, h.title, h.snippet))
        .collect();
    let pack = pack::pack_notes(
        &rows,
        |id| rt.store.get_note_body(id).ok().flatten(),
        max_tokens,
        |text| rt.estimate(text, Class::Prose),
    );
    let text = pack::render(&pack);
    let mut before_bytes = 0u64;
    let mut est_before = 0u32;
    for entry in &pack.entries {
        if let Some(body) = rt.store.get_note_body(entry.id).ok().flatten() {
            before_bytes += body.len() as u64;
            est_before = est_before.saturating_add(rt.estimate(&body, Class::Prose));
        }
    }
    let _ = rt.record(&Measurement {
        plugin: "memory",
        kind: "mem_pack",
        before_bytes,
        after_bytes: text.len() as u64,
        est_before,
        est_after: rt.estimate(&text, Class::Prose),
        ref_id: None,
        call_id: rt.call_id,
    });
    Ok(text)
}

pub fn mem_search(
    rt: &crate::plugin::Runtime,
    query: &str,
    limit: u32,
) -> anyhow::Result<Vec<crate::store::NoteHit>> {
    let lim = limit.max(1);
    let embed = &rt.config.plugins.memory.embed;
    if !embed.enabled {
        return rt.store.search_notes(query, lim);
    }
    if embed.hybrid {
        rt.store.search_notes_hybrid(query, lim, embed)
    } else {
        rt.store.search_notes_embed(query, lim, embed)
    }
}

pub fn mem_get(rt: &crate::plugin::Runtime, id: i32) -> anyhow::Result<Option<String>> {
    let Some(row) = rt.store.note_row(id)? else {
        return Ok(None);
    };
    let body = match row.retired {
        None => row.body,
        Some(ts) => {
            // Nothing is lost (D4): a retired note still reads whole, one line saying why.
            let mut head = format!("retired {ts}");
            if let Some(s) = row.superseded_by {
                head.push_str(&format!(", superseded by {s}"));
            }
            format!("{head}\n{}", row.body)
        }
    };
    let after_bytes = body.len() as u64;
    let est_after = rt.estimate(&body, Class::Prose);
    let _ = rt.record(&Measurement {
        plugin: "memory",
        kind: "mem_get",
        before_bytes: 0,
        after_bytes,
        est_before: 0,
        est_after,
        ref_id: Some(id.to_string()),
        call_id: rt.call_id,
    });
    Ok(Some(body))
}

/// T69.1 lifecycle, one call path for MCP `mem_update` and `rtok memory retire|pin|unpin`:
/// `retire` tombstones the id (never deletes), `pinned` pins or unpins. Retiring can name
/// the replacement with `superseded_by`.
pub fn mem_update(
    rt: &crate::plugin::Runtime,
    id: i32,
    retire: bool,
    superseded_by: Option<i32>,
    pinned: Option<bool>,
) -> anyhow::Result<String> {
    if superseded_by.is_some() && !retire {
        anyhow::bail!("superseded_by needs retire: true (it names what the retired note replaces)");
    }
    let mut parts = Vec::new();
    if retire {
        if !rt.store.retire_note(id, superseded_by)? {
            anyhow::bail!("unknown note id: {id}");
        }
        let mut line = format!("retired note {id}");
        if let Some(s) = superseded_by {
            line.push_str(&format!(", superseded by {s}"));
        }
        parts.push(line);
    }
    if let Some(p) = pinned {
        if !rt.store.set_note_pinned(id, p)? {
            anyhow::bail!("unknown note id: {id}");
        }
        parts.push(format!(
            "{} note {id}",
            if p { "pinned" } else { "unpinned" }
        ));
    }
    if parts.is_empty() {
        anyhow::bail!("nothing to do: pass retire or pinned");
    }
    Ok(parts.join("; "))
}

/// T69.1 revise: save the replacement through the in-place `mem_save` path, then retire
/// the old id naming the replacement. Same title → the upsert already updated the row, so
/// there is nothing to retire. Returns `(replacement id, retired old id)`.
pub fn mem_revise(
    rt: &crate::plugin::Runtime,
    id: i32,
    title: &str,
    body: &str,
) -> anyhow::Result<(i32, Option<i32>)> {
    let Some(old) = rt.store.note_row(id)? else {
        anyhow::bail!("unknown note id: {id}");
    };
    let (new, _) = mem_save(rt, &old.kind, title, body, old.project.as_deref())?;
    if new == id {
        return Ok((new, None));
    }
    rt.store.retire_note(id, Some(new))?;
    Ok((new, Some(id)))
}

/// T472: earlier title and body for `id`, oldest first. The current body stays on
/// `mem_get`. Unknown ids error the same way as retire and pin.
pub fn mem_history(rt: &crate::plugin::Runtime, id: i32) -> anyhow::Result<String> {
    if rt.store.note_row(id)?.is_none() {
        anyhow::bail!("unknown note id: {id}");
    }
    let rows = rt.store.note_versions(id)?;
    if rows.is_empty() {
        return Ok(format!("no earlier versions of note {id}"));
    }
    Ok(rows
        .into_iter()
        .map(|(version, title, body)| format!("v{version} {title}\n{body}"))
        .collect::<Vec<_>>()
        .join("\n\n"))
}

#[cfg(test)]
mod tests {
    use super::*;

    /// One over-long title used to be returned whole, past `cap`.
    #[test]
    fn a_single_long_title_cannot_break_the_index_cap() {
        let cx = crate::plugin::Runtime::in_memory("t327-title-cap").unwrap();
        let ctx = Ctx::new(&cx);
        let cap = 30;
        let mut entries = vec![(1, "x".repeat(2000), 5)];
        let text = render_title_index(&ctx, &mut entries, cap);
        assert!(ctx.estimate(&text, Class::Prose) <= cap, "{text}");
        assert!(text.starts_with(INDEX_GUIDE), "{text}");
        assert!(
            text.contains("1 x"),
            "the entry is shortened, not lost: {text}"
        );
    }

    #[test]
    fn remember_prefix_saves_a_note_and_repeats_same_id() {
        use rtok_plugin_sdk::PromptSubmit;
        let cx = crate::plugin::Runtime::in_memory("t695-remember").unwrap();
        let ctx = Ctx::new(&cx);
        let ev = PromptSubmit {
            prompt: "remember: hooks fail open under 10 ms",
        };
        let first = Memory.prompt_submit(&ev, &ctx).unwrap();
        assert_eq!(first.text, "saved note 1");
        let second = Memory.prompt_submit(&ev, &ctx).unwrap();
        assert_eq!(second.text, "saved note 1");
        let plain = PromptSubmit {
            prompt: "just a question",
        };
        assert!(Memory.prompt_submit(&plain, &ctx).is_none());
    }

    #[test]
    fn prompt_recall_is_on_by_default_and_skips_bodies() {
        use rtok_plugin_sdk::PromptSubmit;
        let cx = crate::plugin::Runtime::in_memory("t695-recall-on").unwrap();
        assert_eq!(cx.config.plugins.memory.prompt_recall, 5);
        mem_save(&cx, "note", "walrus", "the walrus journal lives here", None).unwrap();
        let ctx = Ctx::new(&cx);
        assert!(!mem_search(&cx, "walrus", 5).unwrap().is_empty());
        let ev = PromptSubmit {
            prompt: "walrus journal",
        };
        let inj = Memory
            .prompt_submit(&ev, &ctx)
            .expect("prompt_recall on by default");
        assert!(inj.text.starts_with(INDEX_GUIDE), "{}", inj.text);
        assert!(inj.text.contains(" walrus"), "{}", inj.text);
        assert!(inj.text.contains("t body"), "{}", inj.text);
        assert!(
            !inj.text.contains("journal lives"),
            "titles only: {}",
            inj.text
        );
    }

    #[test]
    fn prompt_recall_off_injects_nothing() {
        use rtok_plugin_sdk::PromptSubmit;
        let mut cx = crate::plugin::Runtime::in_memory("t695-recall-off").unwrap();
        cx.config.plugins.memory.prompt_recall = 0;
        mem_save(&cx, "note", "alpha", "hooks fail open", None).unwrap();
        let ctx = Ctx::new(&cx);
        let ev = PromptSubmit {
            prompt: "tell me about hooks fail open",
        };
        assert!(Memory.prompt_submit(&ev, &ctx).is_none());
    }

    #[test]
    fn save_three_search_hits_first_get_full_body() {
        let cx = crate::plugin::Runtime::in_memory("t61").unwrap();
        let a = mem_save(
            &cx,
            "decision",
            "walrus",
            "the walrus journal lives here",
            Some("rtok"),
        )
        .unwrap()
        .0;
        let _b = mem_save(
            &cx,
            "decision",
            "banana",
            "yellow fruit unrelated",
            Some("rtok"),
        )
        .unwrap();
        let _c = mem_save(
            &cx,
            "decision",
            "other",
            "nothing matching the unique token",
            Some("rtok"),
        )
        .unwrap();
        let hits = mem_search(&cx, "walrus", 5).unwrap();
        assert!(!hits.is_empty(), "{hits:?}");
        assert_eq!(hits[0].title, "walrus");
        assert_eq!(hits[0].id, a);
        assert!(hits[0].snippet.len() <= 120);
        let body = mem_get(&cx, a).unwrap().unwrap();
        assert_eq!(body, "the walrus journal lives here");
        let rows: Vec<_> = cx
            .store
            .list_measurements("memory")
            .unwrap()
            .into_iter()
            .filter(|r| r.kind == "mem_get")
            .collect();
        assert_eq!(rows.len(), 1);
        assert_eq!(
            rows[0].after_bytes,
            i64::try_from(body.len()).unwrap_or(i64::MAX)
        );
    }

    /// T293: recall index shows ids, titles, body token estimates; bodies stay out.
    #[test]
    fn recall_index_names_fetch_without_bodies() {
        let cx = crate::plugin::Runtime::in_memory("t293-index").unwrap();
        let secret = "vault-secret-never-inject";
        let a = mem_save(&cx, "note", "alpha", secret, None).unwrap().0;
        let b = mem_save(&cx, "note", "beta", "plain beta body", None)
            .unwrap()
            .0;
        let c = mem_save(&cx, "note", "gamma", "plain gamma body", None)
            .unwrap()
            .0;
        let inj = recall(&Ctx::new(&cx)).unwrap();
        for id in [a, b, c] {
            assert!(inj.text.contains(&id.to_string()), "{}", inj.text);
        }
        for title in ["alpha", "beta", "gamma"] {
            assert!(inj.text.contains(title), "{}", inj.text);
        }
        assert!(inj.text.contains("t body"), "{}", inj.text);
        assert!(inj.text.contains(INDEX_GUIDE), "{}", inj.text);
        assert!(!inj.text.contains(secret), "{}", inj.text);
        assert!(cx.estimate(&inj.text, Class::Prose) <= 200);
    }

    /// T428: the recall measurement still prices the bodies the titles replace, now from the
    /// one read per note: their summed bytes and token estimates.
    #[test]
    fn recall_measurement_sums_the_bodies_it_replaced() {
        let cx = crate::plugin::Runtime::in_memory("t418-sizes").unwrap();
        let bodies = ["short body", "a longer body, still plain ascii text"];
        for (i, body) in bodies.iter().enumerate() {
            mem_save(&cx, "note", &format!("n{i}"), body, None).unwrap();
        }
        recall(&Ctx::new(&cx)).unwrap();
        let rows = cx.store.list_measurements("memory").unwrap();
        let row = rows
            .iter()
            .find(|r| r.kind == "recall")
            .expect("recall row");
        let bytes: usize = bodies.iter().map(|b| b.len()).sum();
        let est: u32 = bodies.iter().map(|b| cx.estimate(b, Class::Prose)).sum();
        assert_eq!(row.before_bytes, bytes as i64);
        assert_eq!(row.est_before, est as i32);
    }

    /// T293: zero notes for the resolved project inject one line naming that key.
    #[test]
    fn recall_empty_project_names_the_key() {
        let pid = std::process::id();
        let dir = std::env::temp_dir().join(format!("rtok-mem-empty-{pid}"));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(dir.join(".git")).unwrap();
        let name = dir.file_name().unwrap().to_string_lossy().into_owned();
        let mut cx = crate::plugin::Runtime::in_memory("t293-empty").unwrap();
        cx.cwd = Some(dir.to_string_lossy().into_owned());
        mem_save(
            &cx,
            "note",
            "elsewhere",
            "other project body",
            Some("other"),
        )
        .unwrap();
        let inj = recall(&Ctx::new(&cx)).unwrap();
        assert_eq!(inj.text, format!("memory project {name}: no notes"));
        assert!(cx.estimate(&inj.text, Class::Prose) <= 200);
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// T293: `mem_get` records one measurement and returns the full body, prefix included.
    #[test]
    fn mem_get_records_measurement_and_returns_full_body() {
        let cx = crate::plugin::Runtime::in_memory("t293-get").unwrap();
        let id = mem_save(&cx, "note", "t", "verbatim body", None).unwrap().0;
        mem_update(&cx, id, true, None, None).unwrap();
        assert_eq!(cx.store.measurement_count("memory").unwrap(), 0);
        let body = mem_get(&cx, id).unwrap().unwrap();
        assert!(body.starts_with("retired "));
        assert!(body.contains("verbatim body"));
        let rows: Vec<_> = cx
            .store
            .list_measurements("memory")
            .unwrap()
            .into_iter()
            .filter(|r| r.kind == "mem_get")
            .collect();
        assert_eq!(rows.len(), 1);
        assert_eq!(
            rows[0].after_bytes,
            i64::try_from(body.len()).unwrap_or(i64::MAX)
        );
        assert!(rows[0].est_after > 0);
    }

    /// T69.1: revise saves the replacement through `mem_save`, then retires the old row
    /// naming it; the old body's words no longer search, `mem_get` keeps the body.
    #[test]
    fn revise_supersedes_and_retires_the_old_note() {
        let cx = crate::plugin::Runtime::in_memory("t691-revise").unwrap();
        let old = mem_save(&cx, "decision", "auth model", "sessions forever", Some("p"))
            .unwrap()
            .0;
        let (new, retired) = mem_revise(&cx, old, "auth model v2", "jwt tokens now").unwrap();
        assert_eq!(retired, Some(old));
        assert_ne!(new, old);
        assert_eq!(mem_search(&cx, "sessions", 5).unwrap().len(), 0);
        assert_eq!(mem_search(&cx, "jwt", 5).unwrap()[0].id, new);
        let body = mem_get(&cx, old).unwrap().unwrap();
        assert!(body.starts_with("retired "), "{body}");
        assert!(body.contains(&format!(", superseded by {new}")), "{body}");
        assert!(body.contains("sessions forever"), "{body}");
        // Same title → the upsert updated the row in place; nothing retires.
        let (same, none) = mem_revise(&cx, new, "auth model v2", "jwt tokens v2").unwrap();
        assert_eq!((same, none), (new, None));
        assert!(
            mem_get(&cx, new)
                .unwrap()
                .unwrap()
                .starts_with("jwt tokens v2"),
            "revision landed"
        );
    }

    /// T69.1: a retired note never recalls and never searches, but still reads whole.
    #[test]
    fn retire_removes_from_recall_and_keeps_the_body() {
        let cx = crate::plugin::Runtime::in_memory("t691-retire").unwrap();
        let id = mem_save(&cx, "note", "stale fact", "wrong under new light", None)
            .unwrap()
            .0;
        let out = mem_update(&cx, id, true, None, None).unwrap();
        assert_eq!(out, format!("retired note {id}"));
        if let Some(inj) = recall(&Ctx::new(&cx)) {
            assert!(!inj.text.contains("stale fact"), "{}", inj.text);
        }
        let body = mem_get(&cx, id).unwrap().unwrap();
        assert!(body.starts_with("retired "), "{body}");
        assert!(body.contains("wrong under new light"), "{body}");
        assert!(mem_search(&cx, "wrong", 5).unwrap().is_empty());
        assert!(mem_update(&cx, 999, true, None, None).is_err());
        // A re-save revives the topic instead of editing a tombstone.
        let (again, _) = mem_save(&cx, "note", "stale fact", "right again", None).unwrap();
        assert_eq!(mem_get(&cx, again).unwrap().unwrap(), "right again");
    }

    /// T69.1: a pinned note leads recall ahead of twenty newer ones, byte-stable.
    #[test]
    fn pinned_note_leads_recall_byte_stable() {
        let cx = crate::plugin::Runtime::in_memory("t691-pin").unwrap();
        let keep = mem_save(&cx, "note", "keep me first", "pinned body", None)
            .unwrap()
            .0;
        assert_eq!(
            mem_update(&cx, keep, false, None, Some(true)).unwrap(),
            format!("pinned note {keep}")
        );
        for i in 0..20 {
            mem_save(
                &cx,
                "note",
                &format!("newer-{i}"),
                &format!("body-{i}"),
                None,
            )
            .unwrap();
        }
        let a = recall(&Ctx::new(&cx)).unwrap();
        let b = recall(&Ctx::new(&cx)).unwrap();
        assert_eq!(a.text, b.text);
        assert!(
            a.text.contains(&format!("{keep} keep me first")),
            "{}",
            a.text
        );
        assert!(a.text.contains(INDEX_GUIDE), "{}", a.text);
        assert_eq!(
            mem_update(&cx, keep, false, None, Some(false)).unwrap(),
            format!("unpinned note {keep}")
        );
    }

    /// T69.1: the memory tools stay within the 60-description-token surface budget
    /// (`rtok doctor` prices the same strings). T71.2 added `mem_handoff` as the fifth,
    /// so `mem_save` drops the field list the input schema already carries. T460
    /// shortened `mem_update` the same way so `mem_pack` fits; never-delete stays
    /// in the plugin README.
    #[test]
    fn mcp_surface_stays_within_sixty_description_tokens() {
        let cx = crate::plugin::Runtime::in_memory("t691-surface").unwrap();
        let total: i64 = Memory
            .mcp_tools()
            .iter()
            .map(|t| i64::from(cx.estimate(t.description, Class::Prose)))
            .sum();
        assert!(total <= 60, "memory tool surface is {total} tokens");
    }

    #[test]
    fn same_project_kind_title_updates_in_place() {
        let cx = crate::plugin::Runtime::in_memory("t671").unwrap();
        let (a, first) = mem_save(&cx, "decision", "auth model", "sessions", Some("p")).unwrap();
        let (b, second) = mem_save(&cx, "decision", "auth model", "jwt", Some("p")).unwrap();
        assert_eq!((first, second, a == b), (false, true, true));
        // A different kind or project is another topic.
        let (c, _) = mem_save(&cx, "note", "auth model", "other kind", Some("p")).unwrap();
        let (d, _) = mem_save(&cx, "decision", "auth model", "other project", Some("q")).unwrap();
        assert!(a != c && a != d && c != d);
        assert_eq!(cx.store.list_notes(Some("p"), true).unwrap().len(), 2);
        let hits = mem_search(&cx, "jwt", 5).unwrap();
        assert_eq!(hits[0].id, a);
        assert!(mem_search(&cx, "sessions", 5).unwrap().is_empty());
    }

    /// T472: history returns the old body; recall and mem_get stay on the current one.
    #[test]
    fn history_returns_the_old_body_and_recall_stays_on_the_current() {
        let cx = crate::plugin::Runtime::in_memory("t472-history").unwrap();
        let (id, _) = mem_save(&cx, "decision", "topic", "alpha body", None).unwrap();
        mem_save(&cx, "decision", "topic", "beta body", None).unwrap();
        assert_eq!(mem_get(&cx, id).unwrap().unwrap(), "beta body");
        let history = mem_history(&cx, id).unwrap();
        assert!(history.contains("alpha body"), "{history}");
        assert!(!history.contains("beta body"), "{history}");
        let recall = recall(&Ctx::new(&cx)).unwrap();
        assert!(recall.text.contains("topic"), "{}", recall.text);
        assert!(!recall.text.contains("alpha body"), "{}", recall.text);
        assert!(!recall.text.contains("beta body"), "{}", recall.text);
        assert!(mem_history(&cx, 999).is_err());
    }

    #[test]
    fn twenty_notes_recall_five_titles_under_budget_stable() {
        let cx = crate::plugin::Runtime::in_memory("t62").unwrap();
        // `recall` filters by the project derived from the working directory, so the notes
        // must be saved the same way — a hard-coded name only matched a checkout called `rtok`.
        for i in 0..20 {
            mem_save(
                &cx,
                "note",
                &format!("title-{i}"),
                &format!("body-{i} secret"),
                None,
            )
            .unwrap();
        }
        let a = recall(&Ctx::new(&cx)).unwrap();
        let b = recall(&Ctx::new(&cx)).unwrap();
        assert_eq!(a.text, b.text);
        assert!(!a.text.contains("secret"), "{}", a.text);
        assert!(a.text.contains(INDEX_GUIDE), "{}", a.text);
        assert!(a.text.contains("t body"), "{}", a.text);
        assert_eq!(a.text.lines().count(), 6, "{}", a.text);
        assert!(cx.estimate(&a.text, Class::Prose) <= 200);
        assert_eq!(a.priority, 10);
        let rows = cx.store.list_measurements("memory").unwrap();
        assert_eq!(
            rows.iter().filter(|r| r.kind == "recall").count(),
            2,
            "{rows:?}"
        );
    }

    #[test]
    fn recall_filters_by_hook_cwd_not_process_cwd() {
        let pid = std::process::id();
        let dir = std::env::temp_dir().join(format!("rtok-mem-cwd-{pid}"));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(dir.join(".git")).unwrap();
        let mut cx = crate::plugin::Runtime::in_memory("mem-cwd").unwrap();
        cx.cwd = Some(dir.to_string_lossy().into_owned());
        mem_save(
            &cx,
            "note",
            "other-note",
            "body from elsewhere",
            Some("elsewhere"),
        )
        .unwrap();
        // project_name uses the directory's basename (the last component).
        let name = dir.file_name().unwrap().to_string_lossy().into_owned();
        mem_save(
            &cx,
            "note",
            "cwd-note",
            "visible under hook cwd",
            Some(&name),
        )
        .unwrap();
        let inj = recall(&Ctx::new(&cx)).unwrap();
        assert!(inj.text.contains("cwd-note"), "{}", inj.text);
        assert!(!inj.text.contains("other-note"), "{}", inj.text);
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// T133: a note remembered from a linked worktree recalls from the main checkout —
    /// the layout git writes (`.git` file, `commondir`), no git binary needed.
    #[test]
    fn note_saved_from_a_worktree_recalls_from_the_main_checkout() {
        use rtok_plugin_sdk::PromptSubmit;
        let dir = crate::testutil::tmp_dir("t133-recall");
        let (main, wt) = crate::testutil::worktree_layout(&dir);
        let mut cx = crate::plugin::Runtime::in_memory("t133-recall").unwrap();
        cx.cwd = Some(wt.to_string_lossy().into_owned());
        let ev = PromptSubmit {
            prompt: "remember: worktree note",
        };
        Memory.prompt_submit(&ev, &Ctx::new(&cx)).unwrap();
        cx.cwd = Some(main.to_string_lossy().into_owned());
        let inj = recall(&Ctx::new(&cx)).unwrap();
        assert!(inj.text.contains("worktree note"), "{}", inj.text);
        assert_eq!(project_name(&wt).as_deref(), Some("repo"));
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// A project directory holding `src/proxy/semantic_cache.rs`, with a runtime whose cwd is it.
    fn linked_fixture(tag: &str) -> (crate::plugin::Runtime, std::path::PathBuf) {
        let dir = std::env::temp_dir().join(format!("rtok-t374-{tag}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(dir.join("src/proxy")).unwrap();
        std::fs::write(dir.join("src/proxy/semantic_cache.rs"), "x").unwrap();
        let mut cx = crate::plugin::Runtime::in_memory(format!("t374-{tag}")).unwrap();
        cx.cwd = Some(dir.to_string_lossy().into_owned());
        (cx, dir)
    }

    /// Two notes the text query cannot tell apart (same words, same length), one naming a real
    /// file; the unlinked one is saved first so it wins any tie on text alone.
    fn equal_pair(cx: &crate::plugin::Runtime) -> (i32, i32) {
        let plain = mem_save(
            cx,
            "note",
            "plain",
            "eviction policy of the proxy src/proxy/something_other.rs",
            None,
        )
        .unwrap()
        .0;
        let linked = mem_save(
            cx,
            "note",
            "linked",
            "eviction policy of the proxy src/proxy/semantic_cache.rs",
            None,
        )
        .unwrap()
        .0;
        (plain, linked)
    }

    fn recall_order(cx: &crate::plugin::Runtime, prompt: &str) -> Vec<&'static str> {
        use rtok_plugin_sdk::PromptSubmit;
        let ev = PromptSubmit { prompt };
        let Some(inj) = Memory.prompt_submit(&ev, &Ctx::new(cx)) else {
            return Vec::new();
        };
        let mut at: Vec<(usize, &'static str)> = ["plain", "linked"]
            .into_iter()
            .filter_map(|t| inj.text.find(&format!(" {t} (")).map(|i| (i, t)))
            .collect();
        at.sort();
        at.into_iter().map(|(_, t)| t).collect()
    }

    #[test]
    fn a_save_links_the_existing_files_its_body_names() {
        let (cx, dir) = linked_fixture("save");
        let (plain, linked) = equal_pair(&cx);
        let hit = |id| {
            cx.store
                .notes_for_files(None, &["src/proxy/semantic_cache.rs".into()], 5)
                .unwrap()
                .iter()
                .any(|h| h.id == id)
        };
        assert!(hit(linked) && !hit(plain));
        // A re-save under the same title re-derives the links from the new body.
        mem_save(&cx, "note", "linked", "nothing about files now", None).unwrap();
        assert!(!hit(linked));
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn a_save_links_the_checkpoint_paths_under_the_root() {
        let (cx, dir) = linked_fixture("ckpt");
        let abs = dir.join("src/proxy/semantic_cache.rs");
        let body = format!("path {}\npath /elsewhere/x.rs\n", abs.display());
        cx.store
            .upsert_note(None, "checkpoint:t374-ckpt", "compact", &body)
            .unwrap();
        let id = mem_save(&cx, "note", "about it", "no path in this body", None)
            .unwrap()
            .0;
        let hits = cx
            .store
            .notes_for_files(None, &["src/proxy/semantic_cache.rs".into()], 5)
            .unwrap();
        assert_eq!(hits.iter().map(|h| h.id).collect::<Vec<_>>(), vec![id]);
        assert!(
            cx.store
                .notes_for_files(None, &["/elsewhere/x.rs".into(), "x.rs".into()], 5)
                .unwrap()
                .is_empty()
        );
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// The done criterion: a note linked to a file the session has read ranks above an equally
    /// text-matching unlinked note, and only once the file has been read.
    #[test]
    fn a_note_linked_to_a_read_file_outranks_an_equal_text_match() {
        let (cx, dir) = linked_fixture("rank");
        equal_pair(&cx);
        assert_eq!(
            recall_order(&cx, "eviction policy"),
            vec!["plain", "linked"],
            "without the file in play the tie falls to the older note"
        );
        cx.store
            .put_read_cache(
                "t374-rank",
                "src/proxy/semantic_cache.rs\tfull\t",
                "h",
                None,
            )
            .unwrap();
        assert_eq!(
            recall_order(&cx, "eviction policy"),
            vec!["linked", "plain"]
        );
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn a_file_named_in_the_prompt_boosts_its_notes_too() {
        let (cx, dir) = linked_fixture("prompt");
        equal_pair(&cx);
        // The path's own words already favour the linked note in the text list; what the file
        // adds is that the note is found with a `:line` suffix the text query cannot match.
        let order = recall_order(&cx, "eviction policy in src/proxy/semantic_cache.rs:40");
        assert_eq!(order.first(), Some(&"linked"), "{order:?}");
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn a_read_file_only_reranks_text_hits_and_never_recalls_alone() {
        let (cx, dir) = linked_fixture("only");
        equal_pair(&cx);
        cx.store
            .put_read_cache("another-session", "src/proxy/semantic_cache.rs", "h", None)
            .unwrap();
        assert!(recall_order(&cx, "zebra crossing").is_empty());
        cx.store
            .put_read_cache("t374-only", "read\tsrc/proxy/semantic_cache.rs", "h", None)
            .unwrap();
        assert!(
            recall_order(&cx, "zebra crossing").is_empty(),
            "T452: reading a file is not enough to recall its notes"
        );
        let _ = std::fs::remove_dir_all(&dir);
    }

    const NAMED: &str = "src/proxy/semantic_cache.rs";

    /// `count` notes whose words share nothing with `NAMED` or with each other's prompt, each
    /// linked to `NAMED` by hand, so only the file can bring them back.
    fn linked_without_text(cx: &crate::plugin::Runtime, count: usize) {
        for i in 0..count {
            let id = mem_save(
                cx,
                "note",
                &format!("lone{i}"),
                &format!("quartz{i} lichen"),
                None,
            )
            .unwrap()
            .0;
            cx.store.set_note_files(id, &[NAMED.to_string()]).unwrap();
        }
    }

    fn recall_text(cx: &crate::plugin::Runtime, prompt: &str) -> String {
        use rtok_plugin_sdk::PromptSubmit;
        let ev = PromptSubmit { prompt };
        Memory
            .prompt_submit(&ev, &Ctx::new(cx))
            .map_or_else(String::new, |i| i.text)
    }

    fn lone_titles(text: &str) -> usize {
        (0..8)
            .filter(|i| text.contains(&format!(" lone{i} (")))
            .count()
    }

    #[test]
    fn a_file_named_in_the_prompt_recalls_its_note_without_a_text_match() {
        let (cx, dir) = linked_fixture("named");
        linked_without_text(&cx, 1);
        let text = recall_text(&cx, &format!("please look at {NAMED}:40 today"));
        assert!(text.contains(" lone0 ("), "{text}");
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn a_prompt_naming_no_file_recalls_nothing_extra() {
        let (cx, dir) = linked_fixture("nofile");
        linked_without_text(&cx, 2);
        // The second path does not exist, the third climbs out of the root.
        for prompt in [
            "please look at the cache",
            "please look at src/proxy/other.rs",
            "please look at ../semantic_cache.rs",
        ] {
            assert_eq!(recall_text(&cx, prompt), "", "{prompt}");
        }
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn at_most_two_named_file_notes_are_added() {
        let (cx, dir) = linked_fixture("cap");
        linked_without_text(&cx, 4);
        let text = recall_text(&cx, &format!("please look at {NAMED}"));
        assert_eq!(lone_titles(&text), 2, "{text}");
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn named_file_notes_take_slots_from_the_budget_and_never_enlarge_it() {
        let (cx, dir) = linked_fixture("budget");
        for i in 0..7 {
            mem_save(
                &cx,
                "note",
                &format!("hit{i}"),
                "eviction policy text",
                None,
            )
            .unwrap();
        }
        linked_without_text(&cx, 3);
        let prompt = format!("eviction policy of {NAMED}");
        let text_hits = cx.store.search_notes("eviction policy", 10).unwrap();
        let hits = files::recall_hits(&Ctx::new(&cx), &prompt, text_hits, 5);
        assert_eq!(hits.len(), 5, "{hits:?}");
        assert_eq!(
            hits.iter().filter(|h| h.title.starts_with("lone")).count(),
            2
        );
        let text = recall_text(&cx, &prompt);
        assert!(
            Ctx::new(&cx).estimate(&text, rtok_plugin_sdk::Class::Prose) <= 200,
            "{text}"
        );
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn named_file_recall_is_byte_stable() {
        let run = |tag: &str| {
            let (cx, dir) = linked_fixture(tag);
            equal_pair(&cx);
            linked_without_text(&cx, 3);
            let text = recall_text(&cx, &format!("eviction policy of {NAMED}"));
            let _ = std::fs::remove_dir_all(&dir);
            text
        };
        let (a, b) = (run("stable"), run("stable"));
        assert!(a.contains(" lone"), "{a}");
        assert_eq!(a, b);
    }
}
