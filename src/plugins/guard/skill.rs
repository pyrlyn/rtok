// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! T62.1: digest an oversized skill body before Claude Code injects it.
//!
//! `PreToolUse(Skill)` fires before the host appends `SKILL.md` to the context and may
//! deny with a reason the model reads. Over the cap the body is archived and the reason
//! is its markdown map plus the `expand` pointer: lossless, off by default, and every
//! other outcome (small body, host frontmatter keys, unknown path, read error) falls open.
//!
//! T392: `PostToolUse(Skill)` also remembers each load per context window and adds one line
//! when the same skill loads again before a compaction (`note_load`, `forget_loads`).

use rtok_plugin_sdk::{Class, Ctx, Measurement, PostToolUse, PreToolDecision, PreToolUse};
use std::path::{Path, PathBuf};

/// Frontmatter keys the host applies on invocation (Claude Code docs, 2026-09-17); a
/// denied skill would lose them, so such skills always load whole.
const HOST_KEYS: [&str; 4] = ["allowed-tools", "model", "context", "agent"];

pub(super) fn digest(ev: &PreToolUse, cx: &Ctx) -> Option<PreToolDecision> {
    let g = cx.plugin_config::<crate::config::Guard>("guard");
    if !g.skills {
        return None;
    }
    let name = ev.tool_input.get("skill")?.as_str()?.trim();
    let home = crate::config::env_user_home()?;
    let path = resolve(name, cx.cwd().map(Path::new), &home)?;
    let body = std::fs::read_to_string(path).ok()?;
    decide(cx, name, &body, u64::from(g.skill_max_bytes))
}

/// Read-cache namespace of the skill loads (T392); compaction clears it by prefix.
const LOADS: &str = "skill";

fn load_key(name: &str, agent: Option<&str>) -> String {
    // A sub-agent has its own context window (T129), so its loads are keyed apart.
    match agent {
        Some(id) if !id.is_empty() => format!("{LOADS}\t{name}\t{id}"),
        _ => format!("{LOADS}\t{name}"),
    }
}

/// `PostToolUse(Skill)`: remember the load, and when the skill is already in this window say so.
/// Added context rather than a deny: the host may have trimmed the first copy, so blocking
/// the second could leave the model with no body at all.
pub(super) fn note_load(ev: &PostToolUse, cx: &Ctx) -> Option<String> {
    let name = ev.tool_input.get("skill")?.as_str()?.trim();
    if name.is_empty() {
        return None;
    }
    let key = load_key(name, cx.agent_id());
    let earlier = cx.get_read_cache(&key).ok().flatten();
    let _ = cx.put_read_cache(&key, LOADS, None);
    let (_, ts) = earlier?;
    let calls = cx.calls_since(ts).unwrap_or(0);
    Some(format!(
        "rtok: skill {name} was already loaded {calls} calls ago in this context; use that copy instead of loading it again."
    ))
}

/// A compaction drops the loaded bodies, so the next load is a first one again.
pub(super) fn forget_loads(cx: &Ctx) {
    let _ = cx.clear_read_cache(LOADS);
}

/// `SKILL.md` for a skill name: `plugin:skill` → that plugin's `installPath` from
/// `installed_plugins.json`; a bare name → project `.claude/skills`, then the user one.
/// A name carrying a path separator or `..` is not a skill name: fail open.
fn resolve(name: &str, cwd: Option<&Path>, home: &Path) -> Option<PathBuf> {
    if name.is_empty() || name.contains(['/', '\\']) || name.contains("..") {
        return None;
    }
    if let Some((plugin, skill)) = name.split_once(':') {
        let manifest = home
            .join(".claude")
            .join("plugins")
            .join("installed_plugins.json");
        let root = plugin_root(&manifest, plugin, cwd)?;
        return Some(root.join("skills").join(skill).join("SKILL.md"));
    }
    let project = cwd.map(|c| c.join(".claude").join("skills").join(name).join("SKILL.md"));
    match project {
        Some(p) if p.is_file() => Some(p),
        _ => Some(crate::skill_path::skill_md_path(home, name)),
    }
}

/// `plugins."<plugin>@<marketplace>"[i].installPath` — Claude Code `installed_plugins.json`
/// version 2: `scope` and `installPath` were checked on this machine 2026-10-08. `projectPath`
/// for project/local installs is assumed, not verified (the local file has only a user-scope
/// entry); its absence just falls through to the user scope. A skill name has no
/// marketplace and the file keeps one install per scope and project, so anything not
/// provably the right install falls open:
/// a plugin under several marketplaces, or several installs that neither `cwd` nor the
/// user scope narrows to one.
fn plugin_root(manifest: &Path, plugin: &str, cwd: Option<&Path>) -> Option<PathBuf> {
    let text = std::fs::read_to_string(manifest).ok()?;
    let v: serde_json::Value = serde_json::from_str(&text).ok()?;
    let mut keys = v
        .get("plugins")?
        .as_object()?
        .iter()
        .filter(|(k, _)| k.split('@').next() == Some(plugin));
    let (_, installs) = keys.next()?;
    if keys.next().is_some() {
        return None;
    }
    let installs = installs.as_array()?;
    let only = |pick: &dyn Fn(&serde_json::Value) -> bool| {
        let mut hits = installs.iter().filter(|i| pick(i));
        let first = hits.next()?;
        hits.next().is_none().then_some(first)
    };
    let chosen = cwd
        .and_then(|c| {
            only(&|i| i.get("projectPath").and_then(|p| p.as_str()).map(Path::new) == Some(c))
        })
        .or_else(|| only(&|i| i.get("scope").and_then(|s| s.as_str()) == Some("user")))
        .or_else(|| (installs.len() == 1).then(|| &installs[0]))?;
    Some(PathBuf::from(chosen.get("installPath")?.as_str()?))
}

fn decide(cx: &Ctx, name: &str, body: &str, cap: u64) -> Option<PreToolDecision> {
    let bytes = body.len() as u64;
    if bytes <= cap || host_keys(body) {
        return None;
    }
    let id = cx.put_archive(body.as_bytes()).ok()?;
    let lines = body.lines().count();
    // The whole reason stays within the cap it enforces: map + intro + trailer.
    let map = truncate(
        crate::plugins::read::outline::markdown_digest(body),
        (cap as usize).saturating_sub(512),
    );
    let reason = format!(
        "rtok kept the map of skill {name} ({} KB); the full body is archived, not loaded:\n{map}\
         [rtok {id} · {lines} lines · expand: rtok expand {id}]\n\
         Pull one section with `rtok expand {id} --grep <heading>`; set [plugins.guard] skills = false to load skills whole.",
        bytes / 1024
    );
    let _ = cx.record(&Measurement {
        plugin: "guard",
        kind: "skill",
        before_bytes: bytes,
        after_bytes: reason.len() as u64,
        est_before: cx.estimate(body, Class::Prose),
        est_after: cx.estimate(&reason, Class::Prose),
        ref_id: Some(id),
        call_id: None,
    });
    Some(PreToolDecision::Deny { reason })
}

/// True when the YAML frontmatter names a key the host applies at invocation.
fn host_keys(body: &str) -> bool {
    let Some(rest) = body.strip_prefix("---") else {
        return false;
    };
    let end = rest.find("\n---").unwrap_or(rest.len());
    rest[..end].lines().any(|l| {
        HOST_KEYS.iter().any(|k| {
            l.strip_prefix(k)
                .is_some_and(|r| r.trim_start().starts_with(':'))
        })
    })
}

/// Cut at a char boundary with an ellipsis.
fn truncate(mut s: String, cap: usize) -> String {
    if s.len() > cap {
        let mut end = cap;
        while !s.is_char_boundary(end) {
            end -= 1;
        }
        s.truncate(end);
        s.push_str("…\n");
    }
    s
}

#[cfg(test)]
mod tests {
    use super::*;
    use rtok_plugin_sdk::Archive;

    fn body(sections: usize) -> String {
        let mut s = "---\nname: big\ndescription: many sections\n---\n".to_string();
        for i in 0..sections {
            s += &format!("## Section {i}\nFirst line of {i}.\n```sh\n# not a heading\n```\n");
        }
        s
    }

    fn load(cx: &Ctx, skill: &str) -> Option<String> {
        let input = serde_json::json!({ "skill": skill });
        let ev = PostToolUse {
            tool_name: "Skill",
            tool_input: &input,
            tool_response: &serde_json::Value::Null,
        };
        note_load(&ev, cx)
    }

    /// T392: the second load of a skill warns, a load after a compaction does not, and another
    /// context window or another skill is its own first load.
    #[test]
    fn a_repeat_load_warns_until_the_next_compaction() {
        let rt = crate::testutil::runtime("skill-repeat").0;
        let cx = Ctx::new(&rt);
        assert_eq!(load(&cx, "rtok-hub"), None, "first load");
        let warn = load(&cx, "rtok-hub").expect("second load");
        assert!(warn.contains("skill rtok-hub was already loaded"), "{warn}");
        assert_eq!(load(&cx, "other"), None);
        assert_eq!(load(&Ctx::with_agent(&rt, Some("sub")), "rtok-hub"), None);
        forget_loads(&cx);
        assert_eq!(load(&cx, "rtok-hub"), None, "after compaction");
        assert!(load(&cx, "rtok-hub").is_some());
    }

    #[test]
    fn small_bodies_and_host_keys_pass() {
        let cx = crate::testutil::runtime("skill-pass").0;
        let cx = Ctx::new(&cx);
        assert!(decide(&cx, "s", "# T\n\nthree\nlines\n", 8192).is_none());
        let keyed = format!("---\nallowed-tools: Bash\n---\n{}", body(300));
        assert!(keyed.len() > 8192);
        assert!(decide(&cx, "s", &keyed, 8192).is_none());
        assert!(!host_keys(&body(1)));
        assert!(host_keys("---\nmodel:  haiku\n---\n# x"));
    }

    #[test]
    fn oversized_body_is_denied_with_map_and_pointer_under_budget() {
        let rt = crate::testutil::runtime("skill-deny").0;
        let cx = Ctx::new(&rt);
        // ≈ 3,000 lines / ≈ 230 KB: the size the measured outlier had (research §10.2).
        let big = body(600) + &"x".repeat(200_000);
        let t0 = std::time::Instant::now();
        let d = decide(&cx, "big", &big, 8192);
        let ms = t0.elapsed().as_millis();
        let Some(PreToolDecision::Deny { reason }) = d else {
            panic!("{d:?}")
        };
        assert!(
            reason.starts_with("rtok kept the map of skill big ("),
            "{reason}"
        );
        assert!(
            reason.contains("## Section 0\n  First line of 0.\n"),
            "{reason}"
        );
        assert!(!reason.contains("not a heading"), "{reason}");
        assert!(reason.len() <= 8192, "{}", reason.len());
        let id = reason
            .split("expand: rtok expand ")
            .nth(1)
            .and_then(|r| r.split(']').next())
            .unwrap();
        assert!(
            reason.contains(&format!("[rtok {id} · {} lines ·", big.lines().count())),
            "{reason}"
        );
        assert_eq!(rt.get_archive(id).unwrap().unwrap(), big.as_bytes());
        // The 10 ms gate is the release number. Debug only guards against a gross
        // regression; the `windows-latest` runner took 270 ms here (ci run 35591648404),
        // most of it the archive write on NTFS, and 1685 ms under full-suite load (ci run
        // 35960806447). T237: `.config/nextest.toml` runs this test alone, and the Windows
        // debug bound leaves room over that outlier.
        let budget = match (cfg!(debug_assertions), cfg!(windows)) {
            (false, _) => 10,
            (true, false) => 100,
            (true, true) => 3000,
        };
        assert!(ms < budget, "{ms} ms");
    }

    #[test]
    fn resolve_prefers_project_then_user_and_reads_the_plugin_manifest() {
        let home = crate::testutil::tmp_dir("skill-resolve");
        let proj = home.join("proj");
        let p = proj.join(".claude/skills/here/SKILL.md");
        std::fs::create_dir_all(p.parent().unwrap()).unwrap();
        std::fs::write(&p, "# here").unwrap();
        std::fs::create_dir_all(home.join(".claude/plugins")).unwrap();
        std::fs::write(
            home.join(".claude/plugins/installed_plugins.json"),
            r#"{"version":2,"plugins":{"pony@market":[{"installPath":"/cache/pony/1.0"}]}}"#,
        )
        .unwrap();
        assert_eq!(resolve("here", Some(&proj), &home), Some(p));
        assert_eq!(
            resolve("there", Some(&proj), &home),
            Some(crate::skill_path::skill_md_path(&home, "there"))
        );
        assert_eq!(
            resolve("pony:tail", None, &home),
            Some(PathBuf::from("/cache/pony/1.0/skills/tail/SKILL.md"))
        );
        assert_eq!(resolve("nope:tail", None, &home), None);
        assert_eq!(resolve("../etc", None, &home), None);
        assert_eq!(resolve("a/b", None, &home), None);
    }

    fn root_of(manifest: &str, plugin: &str, cwd: Option<&Path>) -> Option<PathBuf> {
        let home = crate::testutil::tmp_dir("skill-plugin-root");
        let file = home.join("installed_plugins.json");
        std::fs::write(&file, manifest).unwrap();
        plugin_root(&file, plugin, cwd)
    }

    #[test]
    fn a_plugin_under_two_marketplaces_falls_open() {
        let m = r#"{"version":2,"plugins":{
            "pony@a":[{"scope":"user","installPath":"/cache/a"}],
            "pony@b":[{"scope":"user","installPath":"/cache/b"}]}}"#;
        assert_eq!(root_of(m, "pony", None), None);
    }

    #[test]
    fn the_install_for_the_session_project_beats_the_user_one() {
        let m = r#"{"version":2,"plugins":{"pony@a":[
            {"scope":"user","installPath":"/cache/user"},
            {"scope":"project","projectPath":"/work/other","installPath":"/cache/other"},
            {"scope":"project","projectPath":"/work/here","installPath":"/cache/here"}]}}"#;
        assert_eq!(
            root_of(m, "pony", Some(Path::new("/work/here"))),
            Some(PathBuf::from("/cache/here"))
        );
    }

    #[test]
    fn the_user_install_is_used_when_no_project_matches_and_ambiguity_falls_open() {
        let m = r#"{"version":2,"plugins":{"pony@a":[
            {"scope":"project","projectPath":"/work/other","installPath":"/cache/other"},
            {"scope":"user","installPath":"/cache/user"}]}}"#;
        assert_eq!(
            root_of(m, "pony", Some(Path::new("/work/here"))),
            Some(PathBuf::from("/cache/user"))
        );
        let two_projects = r#"{"version":2,"plugins":{"pony@a":[
            {"scope":"project","projectPath":"/work/x","installPath":"/cache/x"},
            {"scope":"local","projectPath":"/work/y","installPath":"/cache/y"}]}}"#;
        assert_eq!(
            root_of(two_projects, "pony", Some(Path::new("/work/here"))),
            None
        );
        let two_users = r#"{"version":2,"plugins":{"pony@a":[
            {"scope":"user","installPath":"/cache/1"},{"scope":"user","installPath":"/cache/2"}]}}"#;
        assert_eq!(root_of(two_users, "pony", None), None);
    }

    #[test]
    fn a_single_legacy_entry_without_scope_still_resolves() {
        let m = r#"{"version":2,"plugins":{"pony@a":[{"installPath":"/cache/pony"}]}}"#;
        assert_eq!(
            root_of(m, "pony", Some(Path::new("/work/here"))),
            Some(PathBuf::from("/cache/pony"))
        );
    }
}
