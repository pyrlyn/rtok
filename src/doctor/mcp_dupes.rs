// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! Duplicate MCP entries (T331.4): one agent would start the same server twice. Entries are read
//! with the `rtok_mcp` readers over the surfaces `agents` already knows (`agents::mcp::surfaces`),
//! plus Claude Code's project `.mcp.json` and local `projects.<cwd>` scopes. Two entries are
//! copies when their normalized launch matches: the command resolved through `PATH` and symlinks,
//! the same args and env, `npx -y pkg@x` as the package `pkg`, a URL compared after lowercasing
//! scheme and host and dropping a trailing slash. The same package at two versions is a conflict,
//! not a duplicate. The same name in two scopes is resolved by the host (local over project over
//! user, research.md section 25): the overridden entry is reported as unused. A server a file
//! marks disabled is not running and not compared. Env values take part in the comparison and are
//! never printed. Every copy is fixable (T331.6): `--fix` removes the ones that are not kept or
//! used.
//!
//! A server of an enabled Claude plugin (T331.11) is read from the plugin's `.mcp.json`, with
//! `${CLAUDE_PLUGIN_ROOT}` resolved, under the name `plugin:<plugin>:<server>` that Claude Code
//! gives it; the plugin's copy is the one kept, and a plugin's file is never fixable.
//!
//! rtok's own entries (T331.10, D33/T332) are not compared by launch; an entry that runs the rtok
//! binary is rtok's, and install and update own the one named `rtok` in the host's own config
//! file. That entry is the kept copy and is never fixable, because the next update writes it
//! again. A second `rtok` under the same name in another scope, or a plugin's copy (Gemini's
//! extension manifest), is the host's to merge or shadow: it is `own-mcp` information, not in
//! the `--fix` kinds, and the plugin copy points at `rtok agents update <host>`. Only a
//! hand-written copy under another name (`rtok-mcp` running `rtok mcp`) starts a second process:
//! it is a `duplicate-mcp` extra and removable. Devin's plugin store is undocumented and
//! Antigravity has no config entry beside its plugin, so neither is read.

use std::collections::BTreeMap;
use std::path::{Path, PathBuf};

use rtok_mcp::config::{self, Fs as McpFs};
use rtok_mcp::registry::RTOK;
use rtok_mcp::spec::McpSpec;
use serde_json::Value;

use super::hooks::{Probes, Problem};
use super::probe::Fs;

/// Where an entry lives, which decides the copy to keep and which same-name entry the host uses.
#[derive(Clone, Copy, PartialEq)]
enum Scope {
    /// An enabled Claude plugin's `.mcp.json`. The plugin owns the file, so this copy is the one
    /// kept (the hand-written entry is the one the user can remove).
    Plugin,
    Project,
    User,
    Local,
}

impl Scope {
    /// Keep order, best first: the same rule as for hooks (project, user, local).
    fn keep(self) -> u8 {
        self as u8
    }

    /// Claude Code resolves a same-name entry by precedence: local, project, user.
    fn wins(self) -> u8 {
        match self {
            Scope::Local => 3,
            Scope::Project => 2,
            Scope::User => 1,
            // Plugin servers are named `plugin:<plugin>:<server>`, so they never share a name.
            Scope::Plugin => 0,
        }
    }

    fn why(self) -> &'static str {
        match self {
            Scope::Project => "shared project file",
            Scope::User => "user file",
            Scope::Local => "local scope",
            Scope::Plugin => "enabled plugin",
        }
    }
}

/// One table of servers: the host's agent, its effective set, and the reader spec.
struct Source {
    agent: &'static str,
    set: String,
    spec: McpSpec,
    scope: Scope,
    /// The plugin id and install directory for a plugin's file.
    plugin: Option<(String, PathBuf)>,
}

/// An enabled server entry as found.
struct Srv {
    agent: &'static str,
    set: String,
    source: String,
    path: String,
    name: String,
    /// What is printed: the command and args, or the URL. Never env.
    shown: String,
    /// The normalized launch, env included, version left out.
    key: String,
    version: Option<String>,
    scope: Scope,
    spec: McpSpec,
    /// Runs the rtok binary: rtok's own, judged by the D33 rules instead of by launch.
    own: bool,
}

/// Where an entry sits in its file, for `--fix` to remove it (T331.6): the table and the name.
pub(super) struct Loc {
    pub source: String,
    pub path: String,
    pub spec: McpSpec,
    pub name: String,
}

/// The location of every entry the check reads; a finding's `source` and `path` find it.
/// A plugin's file is never edited (its copy is not fixable), so its entries are not read here.
pub(super) fn locations(cfg: &crate::config::Config, p: &Probes) -> Vec<Loc> {
    entries(cfg, p, &[])
        .into_iter()
        .map(|s| Loc {
            source: s.source,
            path: s.path,
            spec: s.spec,
            name: s.name,
        })
        .collect()
}

/// The `rtok_mcp` reader over the injected [`Fs`], so mocks serve it too.
struct Reader<'a>(&'a dyn Fs);

impl McpFs for Reader<'_> {
    fn read(&self, path: &Path) -> Option<Vec<u8>> {
        self.0.read(path).ok().map(String::into_bytes)
    }
    fn write(&mut self, _: &Path, _: Vec<u8>) -> anyhow::Result<()> {
        anyhow::bail!("the doctor check never writes")
    }
}

fn sources(cfg: &crate::config::Config, p: &Probes, plugins: &[(String, PathBuf)]) -> Vec<Source> {
    let mut out: Vec<Source> = Vec::new();
    let cwd = p.env.cwd().filter(|d| !d.as_os_str().is_empty());
    for agent in crate::agents::HOSTS
        .iter()
        .filter_map(|id| crate::agents::host(id))
    {
        for v in agent.variants() {
            for s in crate::agents::mcp::surfaces(agent, cfg, v.kind) {
                let set = format!("{}:{}", agent.id(), s.label);
                let mut add = |spec: McpSpec, scope, plugin| {
                    let seen = |o: &Source| {
                        o.set == set
                            && o.spec.config_path == spec.config_path
                            && o.spec.key_path == spec.key_path
                    };
                    if !out.iter().any(seen) {
                        out.push(Source {
                            agent: agent.id(),
                            set: set.clone(),
                            spec,
                            scope,
                            plugin,
                        });
                    }
                };
                if agent.id() == "gemini" {
                    let manifest = McpSpec {
                        config_path: crate::agents::gemini::plugin_manifest(cfg),
                        ..s.spec.clone()
                    };
                    add(manifest, Scope::Plugin, None);
                }
                if let (true, Some(cwd)) = (agent.id() == "claude" && s.label == "cli", &cwd) {
                    let project = McpSpec {
                        config_path: cwd.join(".mcp.json"),
                        key_path: vec!["mcpServers".into()],
                        ..s.spec.clone()
                    };
                    let local = McpSpec {
                        key_path: ["projects", &cwd.display().to_string(), "mcpServers"]
                            .map(String::from)
                            .into(),
                        ..s.spec.clone()
                    };
                    add(project, Scope::Project, None);
                    add(local, Scope::Local, None);
                    for (id, dir) in plugins {
                        let spec = McpSpec {
                            config_path: dir.join(".mcp.json"),
                            key_path: vec!["mcpServers".into()],
                            ..s.spec.clone()
                        };
                        add(spec, Scope::Plugin, Some((id.clone(), dir.clone())));
                    }
                }
                add(s.spec, Scope::User, None);
            }
        }
    }
    out
}

/// A server entry as `(shown, launch key, version)`; `None` for what is not running.
fn launch(entry: &Value, p: &Probes) -> Option<(String, String, Option<String>)> {
    let get = |k: &str| entry.get(k).and_then(Value::as_str);
    let disabled = entry.get("disabled") == Some(&Value::Bool(true))
        || entry.get("enabled") == Some(&Value::Bool(false));
    if disabled {
        return None;
    }
    let env: BTreeMap<&str, &Value> = entry
        .get("env")
        .and_then(Value::as_object)
        .into_iter()
        .flatten()
        .map(|(k, v)| (k.as_str(), v))
        .collect();
    if let Some(url) = ["url", "serverUrl", "httpUrl"].iter().find_map(|k| get(k)) {
        let norm = url::Url::parse(url).map_or_else(
            |_| url.to_lowercase(),
            |u| u.as_str().trim_end_matches('/').to_string(),
        );
        let key = format!("url {norm} {} {env:?}", get("type").unwrap_or_default());
        return Some((url.to_string(), key, None));
    }
    // opencode and kilo spell the whole argv as one `command` array.
    let mut argv: Vec<String> = match entry.get("command")? {
        Value::String(c) => vec![c.clone()],
        Value::Array(a) => a
            .iter()
            .filter_map(Value::as_str)
            .map(String::from)
            .collect(),
        _ => return None,
    };
    argv.extend(
        entry
            .get("args")
            .and_then(Value::as_array)
            .into_iter()
            .flatten()
            .filter_map(Value::as_str)
            .map(String::from),
    );
    let shown = argv.join(" ");
    let (program, rest) = argv.split_first()?;
    let base = Path::new(program)
        .file_name()
        .map_or_else(String::new, |n| n.to_string_lossy().into_owned());
    if ["npx", "bunx", "pnpx", "uvx"].contains(&base.as_str()) {
        let mut rest = rest.iter().skip_while(|a| a.starts_with('-'));
        if let Some(spec) = rest.next() {
            let at = spec.rfind('@').filter(|i| *i > 0);
            let (pkg, version) = match at {
                Some(i) => (&spec[..i], Some(spec[i + 1..].to_string())),
                None => (spec.as_str(), None),
            };
            let tail: Vec<&String> = rest.collect();
            return Some((shown, format!("{base} {pkg} {tail:?} {env:?}"), version));
        }
    }
    let path = if program.contains(['/', '\\']) {
        program.into()
    } else {
        p.which.find(program).unwrap_or_else(|| program.into())
    };
    let program = p.fs.canonical(&path).display().to_string();
    Some((shown, format!("{program} {rest:?} {env:?}"), None))
}

/// `${CLAUDE_PLUGIN_ROOT}` in every string of a plugin's entry, as Claude Code resolves it.
fn expand_root(v: &mut Value, root: &str) {
    match v {
        Value::String(s) => *s = s.replace("${CLAUDE_PLUGIN_ROOT}", root),
        Value::Array(a) => a.iter_mut().for_each(|x| expand_root(x, root)),
        Value::Object(o) => o.values_mut().for_each(|x| expand_root(x, root)),
        _ => {}
    }
}

fn entries(cfg: &crate::config::Config, p: &Probes, plugins: &[(String, PathBuf)]) -> Vec<Srv> {
    let mut out = Vec::new();
    for s in sources(cfg, p, plugins) {
        // A file that does not parse is `agents info`'s to report, not a duplicate check's.
        let Ok(Some(servers)) = config::read_servers(&Reader(p.fs), &s.spec) else {
            continue;
        };
        for (name, entry) in &servers {
            let mut entry = entry.clone();
            // Claude Code scopes a plugin's server as `plugin:<plugin>:<server>`.
            let name = match &s.plugin {
                Some((id, dir)) => {
                    expand_root(&mut entry, &dir.display().to_string());
                    format!("plugin:{}:{name}", id.split('@').next().unwrap_or(id))
                }
                None => name.clone(),
            };
            if let Some((shown, key, version)) = launch(&entry, p) {
                out.push(Srv {
                    agent: s.agent,
                    set: s.set.clone(),
                    source: s.spec.config_path.display().to_string(),
                    path: format!("{}.{name}", s.spec.key_path.join(".")),
                    name,
                    shown,
                    key,
                    version,
                    scope: s.scope,
                    spec: s.spec.clone(),
                    own: rtok_agent_sdk::runs_bin(&entry, crate::agents::is_rtok_bin),
                });
            }
        }
    }
    out
}

fn problem(s: &Srv, kind: &'static str, group: u32, keep: bool, detail: String) -> Problem {
    Problem {
        kind,
        agent: s.agent,
        source: s.source.clone(),
        path: s.path.clone(),
        event: String::new(),
        matcher: None,
        command: s.shown.clone(),
        detail,
        // The plugin owns its `.mcp.json` (T331.11).
        fixable: kind == "duplicate-mcp" && s.scope != Scope::Plugin,
        group: Some(group),
        keep,
    }
}

/// Every group of copies, shadowed entries and version conflicts as one finding per entry.
pub fn check(
    cfg: &crate::config::Config,
    p: &Probes,
    plugins: &[(String, PathBuf)],
) -> Vec<Problem> {
    let (own, all): (Vec<Srv>, Vec<Srv>) =
        entries(cfg, p, plugins).into_iter().partition(|s| s.own);
    let mut out = Vec::new();
    let mut group = 0u32;
    let mut next = || {
        group += 1;
        group - 1
    };
    // The host uses one entry per name; the others never start.
    let mut by_name: BTreeMap<(&str, &str), Vec<&Srv>> = BTreeMap::new();
    for s in &all {
        by_name.entry((&s.set, &s.name)).or_default().push(s);
    }
    let mut live: Vec<&Srv> = Vec::new();
    for copies in by_name.values() {
        let used = copies
            .iter()
            .max_by_key(|s| s.scope.wins())
            .copied()
            .unwrap_or(copies[0]);
        live.push(used);
        if copies.len() > 1 {
            let g = next();
            for s in copies {
                let detail = if std::ptr::eq(*s, used) {
                    format!(
                        "`{}` is defined {} times; the host uses this one",
                        s.name,
                        copies.len()
                    )
                } else {
                    format!(
                        "`{}` is defined {} times; unused, overridden by another scope",
                        s.name,
                        copies.len()
                    )
                };
                out.push(problem(
                    s,
                    "duplicate-mcp",
                    g,
                    std::ptr::eq(*s, used),
                    detail,
                ));
            }
        }
    }
    let mut same: BTreeMap<(&str, &str, &Option<String>), Vec<&Srv>> = BTreeMap::new();
    let mut versions: BTreeMap<(&str, &str), Vec<&Srv>> = BTreeMap::new();
    for s in &live {
        same.entry((&s.set, &s.key, &s.version))
            .or_default()
            .push(s);
        versions.entry((&s.set, &s.key)).or_default().push(s);
    }
    for copies in same.values().filter(|c| c.len() > 1) {
        let g = next();
        // The first of equals is the first in load order.
        let keep = copies
            .iter()
            .min_by_key(|s| s.scope.keep())
            .copied()
            .unwrap_or(copies[0]);
        for s in copies {
            let me = std::ptr::eq(*s, keep);
            let detail = if me {
                format!(
                    "runs {} times; keep this copy ({})",
                    copies.len(),
                    s.scope.why()
                )
            } else {
                format!("runs {} times; an extra copy", copies.len())
            };
            out.push(problem(s, "duplicate-mcp", g, me, detail));
        }
    }
    for copies in versions.values() {
        let mut v: Vec<&Option<String>> = copies.iter().map(|s| &s.version).collect();
        v.sort();
        v.dedup();
        if v.len() > 1 {
            let g = next();
            for s in copies {
                let at = s.version.as_deref().unwrap_or("unpinned");
                let detail = format!(
                    "same package at {} versions, not duplicates; this one is {at}",
                    v.len()
                );
                out.push(problem(s, "conflicting-mcp", g, false, detail));
            }
        }
    }
    out.extend(own_findings(&own, &mut next));
    out
}

/// rtok's own entries per host surface (T331.10). The kept copy is the entry `rtok` in the file
/// install writes, else the best-ranked one named `rtok`, else the best-ranked: never fixable.
fn own_findings(own: &[Srv], next: &mut dyn FnMut() -> u32) -> Vec<Problem> {
    let mut sets: BTreeMap<&str, Vec<&Srv>> = BTreeMap::new();
    for s in own {
        sets.entry(&s.set).or_default().push(s);
    }
    let mut out = Vec::new();
    for copies in sets.values().filter(|c| c.len() > 1) {
        let rank = |s: &&Srv| (s.name != RTOK.name, s.scope != Scope::User, s.scope.keep());
        let Some(kept) = copies.iter().copied().min_by_key(rank) else {
            continue;
        };
        let host = kept.agent;
        let (extra, info): (Vec<&Srv>, Vec<&Srv>) = copies
            .iter()
            .copied()
            .filter(|s| !std::ptr::eq(*s, kept))
            .partition(|s| s.name != RTOK.name && s.scope != Scope::Plugin);
        // The host starts one process per name; a plugin copy or a same-name one is merged.
        let used = copies
            .iter()
            .filter(|s| s.name == RTOK.name && s.scope != Scope::Plugin)
            .max_by_key(|s| s.scope.wins())
            .map_or("the config", |s| s.scope.why());
        // The kept copy is what install writes again, so removing it would only be undone.
        let own_problem = |s: &Srv, kind: &'static str, g: u32, keep: bool, detail: String| {
            let found = problem(s, kind, g, keep, detail);
            Problem {
                fixable: found.fixable && !keep,
                ..found
            }
        };
        if !extra.is_empty() {
            let (g, n) = (next(), extra.len() + 1);
            out.push(own_problem(
                kept,
                "duplicate-mcp",
                g,
                true,
                format!("runs {n} times; keep this copy (rtok's own entry, install writes it)"),
            ));
            for s in &extra {
                let detail = format!("runs {n} times; an extra copy that also launches rtok");
                out.push(own_problem(s, "duplicate-mcp", g, false, detail));
            }
        }
        if !info.is_empty() {
            let (g, n) = (next(), info.len() + 1);
            let said = format!("rtok is configured {n} times; information only");
            if extra.is_empty() {
                let detail = format!("{said}; this is the entry install writes, kept");
                out.push(own_problem(kept, "own-mcp", g, true, detail));
            }
            for s in &info {
                let why = if s.scope == Scope::Plugin {
                    format!(
                        "the host shadows or merges the plugin copy; `rtok agents update {host}` \
                         brings it up to date"
                    )
                } else {
                    format!("the host uses the {used} entry named `rtok` and merges the rest")
                };
                out.push(own_problem(
                    s,
                    "own-mcp",
                    g,
                    false,
                    format!("{said}: {why}"),
                ));
            }
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::Config;
    use crate::doctor::probe::{Env, PathKind, Which};
    use std::io;
    use std::path::PathBuf;

    #[derive(Default)]
    struct Mock {
        files: BTreeMap<PathBuf, String>,
        links: BTreeMap<PathBuf, PathBuf>,
        path: BTreeMap<String, PathBuf>,
    }

    impl Fs for Mock {
        fn canonical(&self, path: &Path) -> PathBuf {
            self.links.get(path).cloned().unwrap_or_else(|| path.into())
        }
        fn read(&self, path: &Path) -> io::Result<String> {
            self.files
                .get(path)
                .cloned()
                .ok_or_else(|| io::Error::from(io::ErrorKind::NotFound))
        }
        fn kind(&self, _: &Path) -> PathKind {
            PathKind::Missing
        }
    }
    impl Env for Mock {
        fn var(&self, _: &str) -> Option<String> {
            None
        }
        fn home(&self) -> Option<PathBuf> {
            Some("/h".into())
        }
        fn cwd(&self) -> Option<PathBuf> {
            Some("/proj".into())
        }
    }
    impl Which for Mock {
        fn find(&self, program: &str) -> Option<PathBuf> {
            self.path.get(program).cloned()
        }
    }

    fn cfg() -> Config {
        let mut c = Config::default();
        c.doctor.claude_json = "/h/.claude.json".into();
        c.setup.codex.config_path = "/h/.codex/config.toml".into();
        c.setup.gemini.dir = "/h/.gemini".into();
        c
    }

    fn run(m: &Mock) -> Vec<Problem> {
        run_with(m, &[])
    }

    fn run_with(m: &Mock, plugins: &[(String, PathBuf)]) -> Vec<Problem> {
        check(
            &cfg(),
            &Probes {
                fs: m,
                env: m,
                which: m,
            },
            plugins,
        )
    }

    fn plugin(m: &mut Mock, id: &str, mcp: &str) -> Vec<(String, PathBuf)> {
        let dir = PathBuf::from("/h/plug").join(id.split('@').next().unwrap());
        m.files.insert(dir.join(".mcp.json"), mcp.into());
        vec![(id.to_string(), dir)]
    }

    fn user(m: &mut Mock, servers: &str) {
        m.files.insert(
            "/h/.claude.json".into(),
            format!(r#"{{"mcpServers": {{{servers}}}}}"#),
        );
    }

    /// `(kind, name, keep)` of every finding, for the groups' shape.
    fn shape(found: &[Problem]) -> Vec<(&'static str, String, bool)> {
        found
            .iter()
            .map(|p| {
                (
                    p.kind,
                    p.path.rsplit('.').next().unwrap_or_default().to_string(),
                    p.keep,
                )
            })
            .collect()
    }

    #[cfg(unix)] // POSIX paths
    #[test]
    fn two_names_with_one_launch_are_a_group_and_env_values_never_appear() {
        let mut m = Mock::default();
        user(
            &mut m,
            r#""a": {"command": "/bin/tool", "args": ["--x"], "env": {"TOKEN": "s3cret"}},
               "b": {"command": "/bin/tool", "args": ["--x"], "env": {"TOKEN": "s3cret"}},
               "c": {"command": "/bin/tool", "args": ["--y"], "env": {"TOKEN": "s3cret"}},
               "d": {"command": "/bin/tool", "args": ["--x"], "env": {"TOKEN": "other"}}"#,
        );
        let found = run(&m);
        assert_eq!(
            shape(&found),
            [
                ("duplicate-mcp", "a".into(), true),
                ("duplicate-mcp", "b".into(), false)
            ]
        );
        assert_eq!(found[0].detail, "runs 2 times; keep this copy (user file)");
        assert_eq!(found[0].command, "/bin/tool --x");
        let all = format!("{found:?}{}", crate::doctor::dupes::render_mcp(&found));
        assert!(!all.contains("s3cret") && !all.contains("TOKEN"), "{all}");
    }

    #[cfg(unix)]
    #[test]
    fn path_and_symlink_resolution_make_a_bare_command_equal_its_binary() {
        let mut m = Mock::default();
        m.path.insert("tool".into(), "/usr/local/bin/tool".into());
        m.links
            .insert("/usr/local/bin/tool".into(), "/opt/tool/bin/tool".into());
        user(
            &mut m,
            r#""a": {"command": "tool"}, "b": {"command": "/opt/tool/bin/tool"}, "c": {"command": "other"}"#,
        );
        assert_eq!(run(&m).len(), 2);
    }

    #[cfg(unix)]
    #[test]
    fn npx_versions_conflict_and_equal_ones_duplicate() {
        let mut m = Mock::default();
        user(
            &mut m,
            r#""a": {"command": "npx", "args": ["-y", "pkg@1.0.0"]},
               "b": {"command": "npx", "args": ["--yes", "pkg@2.0.0"]},
               "s1": {"command": "npx", "args": ["-y", "@sc/x@3"]},
               "s2": {"command": "npx", "args": ["@sc/x@3"]}"#,
        );
        let found = run(&m);
        assert_eq!(
            shape(&found),
            [
                ("duplicate-mcp", "s1".into(), true),
                ("duplicate-mcp", "s2".into(), false),
                ("conflicting-mcp", "a".into(), false),
                ("conflicting-mcp", "b".into(), false),
            ]
        );
        assert!(found[2].detail.contains("2 versions") && found[2].detail.ends_with("1.0.0"));
    }

    #[cfg(unix)]
    #[test]
    fn urls_compare_after_lowercasing_host_and_dropping_a_trailing_slash() {
        let mut m = Mock::default();
        user(
            &mut m,
            r#""a": {"type": "http", "url": "HTTPS://Example.COM/mcp/"},
               "b": {"type": "http", "url": "https://example.com/mcp"},
               "c": {"type": "sse", "url": "https://example.com/mcp"},
               "d": {"type": "http", "url": "https://example.com/Mcp"}"#,
        );
        assert_eq!(shape(&run(&m)).len(), 2);
    }

    #[cfg(unix)]
    #[test]
    fn disabled_servers_are_not_compared_and_a_lone_rtok_entry_is_silent() {
        let mut m = Mock::default();
        user(
            &mut m,
            r#""a": {"command": "/bin/t", "disabled": true}, "b": {"command": "/bin/t"},
               "c": {"command": "/bin/u", "enabled": false}, "d": {"command": "/bin/u"},
               "rtok": {"command": "rtok", "args": ["mcp"]},
               "rtok-mcp": {"command": "rtok", "args": ["mcp"], "disabled": true}"#,
        );
        assert!(run(&m).is_empty());
    }

    const OWN: &str = r#"{"command": "rtok", "args": ["mcp", "--host", "claude"]}"#;
    const HAND: &str = r#"{"type": "stdio", "command": "/opt/bin/rtok", "args": ["mcp"]}"#;

    /// `(kind, name, keep, fixable)` of every finding.
    fn own(found: &[Problem]) -> Vec<(&'static str, String, bool, bool)> {
        shape(found)
            .into_iter()
            .zip(found)
            .map(|((kind, name, keep), p)| (kind, name, keep, p.fixable))
            .collect()
    }

    #[cfg(unix)]
    #[test]
    fn rtok_mcp_under_another_name_is_a_removable_double_launch_next_to_the_kept_rtok() {
        let mut m = Mock::default();
        user(&mut m, &format!(r#""rtok": {OWN}, "rtok-mcp": {HAND}"#));
        let found = run(&m);
        assert_eq!(
            own(&found),
            [
                ("duplicate-mcp", "rtok".into(), true, false),
                ("duplicate-mcp", "rtok-mcp".into(), false, true)
            ]
        );
        assert!(found[0].detail.starts_with("runs 2 times; keep this copy"));
        assert_eq!(found[1].command, "/opt/bin/rtok mcp");
    }

    #[cfg(unix)]
    #[test]
    fn the_rtok_config_entry_is_kept_even_when_a_wider_scope_names_it_too() {
        let mut m = Mock::default();
        user(&mut m, &format!(r#""rtok": {OWN}"#));
        m.files.insert(
            "/proj/.mcp.json".into(),
            format!(r#"{{"mcpServers": {{"rtok": {OWN}, "rtok-mcp": {HAND}}}}}"#),
        );
        let found = run(&m);
        let kept: Vec<&str> = found
            .iter()
            .filter(|p| p.keep)
            .map(|p| p.source.as_str())
            .collect();
        assert_eq!(kept, ["/h/.claude.json"]);
        // The project copy of `rtok` is the host's to merge; the other name is removable.
        let by: Vec<_> = found.iter().map(|p| (p.kind, p.fixable)).collect();
        assert!(by.contains(&("own-mcp", false)) && by.contains(&("duplicate-mcp", true)));
        assert_eq!(found.len(), 3, "{found:?}");
    }

    #[cfg(unix)]
    #[test]
    fn the_same_rtok_name_in_two_scopes_is_information_only_and_names_the_scope_the_host_uses() {
        let mut m = Mock::default();
        m.files.insert(
            "/h/.claude.json".into(),
            format!(
                r#"{{"mcpServers": {{"rtok": {OWN}}},
                    "projects": {{"/proj": {{"mcpServers": {{"rtok": {OWN}}}}}}}}}"#
            ),
        );
        m.files.insert(
            "/proj/.mcp.json".into(),
            format!(r#"{{"mcpServers": {{"rtok": {OWN}}}}}"#),
        );
        let found = run(&m);
        assert_eq!(found.len(), 3);
        assert!(found.iter().all(|p| p.kind == "own-mcp" && !p.fixable));
        assert_eq!(found.iter().filter(|p| p.keep).count(), 1);
        assert!(
            found[1].detail.contains("information only")
                && found[1]
                    .detail
                    .contains("the host uses the local scope entry"),
            "{}",
            found[1].detail
        );
        let text = crate::doctor::dupes::render_mcp(&found);
        assert!(text.contains("rtok is configured 3 times"), "{text}");
        assert!(text.contains("info  /proj/.mcp.json"), "{text}");
    }

    #[cfg(unix)]
    #[test]
    fn a_gemini_extension_copy_is_information_with_the_update_hint() {
        let mut m = Mock::default();
        let entry = r#"{"mcpServers": {"rtok": {"command": "rtok", "args": ["mcp"]}}}"#;
        m.files
            .insert("/h/.gemini/settings.json".into(), entry.into());
        m.files.insert(
            "/h/.gemini/extensions/rtok/gemini-extension.json".into(),
            format!(r#"{{"name": "rtok", "version": "1", {}"#, &entry[1..]),
        );
        let found = run(&m);
        assert_eq!(
            own(&found),
            [
                ("own-mcp", "rtok".into(), true, false),
                ("own-mcp", "rtok".into(), false, false)
            ]
        );
        assert!(found.iter().all(|p| p.agent == "gemini"));
        assert_eq!(found[0].source, "/h/.gemini/settings.json");
        assert!(found[1].source.ends_with("gemini-extension.json"));
        assert!(
            found[1].detail.contains("`rtok agents update gemini`"),
            "{}",
            found[1].detail
        );
        // Without the extension on disk there is one entry and nothing to say.
        m.files.remove(Path::new(
            "/h/.gemini/extensions/rtok/gemini-extension.json",
        ));
        assert!(run(&m).is_empty());
    }

    #[cfg(unix)]
    #[test]
    fn an_enabled_claude_plugin_copy_of_rtok_is_information_and_a_toml_host_follows_the_rules() {
        let mut m = Mock::default();
        user(&mut m, &format!(r#""rtok": {OWN}"#));
        let plugins = plugin(
            &mut m,
            "rtok@mkt",
            &format!(r#"{{"mcpServers": {{"rtok": {OWN}}}}}"#),
        );
        let found = run_with(&m, &plugins);
        assert_eq!(
            own(&found),
            [
                ("own-mcp", "rtok".into(), true, false),
                ("own-mcp", "plugin:rtok:rtok".into(), false, false)
            ]
        );
        assert!(found[1].detail.contains("`rtok agents update claude`"));
        m.files.insert(
            "/h/.codex/config.toml".into(),
            "[mcp_servers.rtok]\ncommand = \"rtok\"\nargs = [\"mcp\"]\n[mcp_servers.rtok-mcp]\ncommand = \"rtok\"\nargs = [\"mcp\"]\n".into(),
        );
        let codex: Vec<_> = run(&m).into_iter().filter(|p| p.agent == "codex").collect();
        assert_eq!(
            own(&codex),
            [
                ("duplicate-mcp", "rtok".into(), true, false),
                ("duplicate-mcp", "rtok-mcp".into(), false, true)
            ]
        );
    }

    #[cfg(unix)]
    #[test]
    fn a_hand_written_double_launch_and_a_plugin_copy_make_two_groups_with_the_entry_listed_once() {
        let mut m = Mock::default();
        let both = format!(r#"{{"mcpServers": {{"rtok": {OWN}, "rtok-mcp": {HAND}}}}}"#);
        m.files.insert("/h/.gemini/settings.json".into(), both);
        m.files.insert(
            "/h/.gemini/extensions/rtok/gemini-extension.json".into(),
            format!(r#"{{"mcpServers": {{"rtok": {OWN}}}}}"#),
        );
        let found = run(&m);
        assert_eq!(
            own(&found),
            [
                ("duplicate-mcp", "rtok".into(), true, false),
                ("duplicate-mcp", "rtok-mcp".into(), false, true),
                ("own-mcp", "rtok".into(), false, false)
            ]
        );
        assert_ne!(found[0].group, found[2].group);
    }

    #[cfg(unix)]
    #[test]
    fn the_same_name_in_two_scopes_is_shadowed_by_local_then_project_then_user() {
        let mut m = Mock::default();
        m.files.insert(
            "/h/.claude.json".into(),
            r#"{"mcpServers": {"x": {"command": "/bin/user"}},
                "projects": {"/proj": {"mcpServers": {"x": {"command": "/bin/local"}}}}}"#
                .into(),
        );
        m.files.insert(
            "/proj/.mcp.json".into(),
            r#"{"mcpServers": {"x": {"command": "/bin/project"}}}"#.into(),
        );
        let found = run(&m);
        let by: Vec<(&str, bool)> = found.iter().map(|p| (p.source.as_str(), p.keep)).collect();
        assert_eq!(
            by,
            [
                ("/proj/.mcp.json", false),
                ("/h/.claude.json", true),
                ("/h/.claude.json", false)
            ]
        );
        assert!(found[1].path.starts_with("projects./proj.mcpServers"));
        assert!(found[0].detail.contains("unused"), "{}", found[0].detail);
        assert!(found.iter().all(|p| p.fixable));
    }

    #[cfg(unix)]
    #[test]
    fn a_plugin_server_and_a_hand_written_copy_are_a_group_that_keeps_the_plugin() {
        let mut m = Mock::default();
        user(
            &mut m,
            r#""mine": {"command": "/h/plug/demo/bin/srv", "args": ["--x"]},
               "other": {"command": "/bin/o"}"#,
        );
        let plugins = plugin(
            &mut m,
            "demo@mkt",
            r#"{"mcpServers": {"srv": {"command": "${CLAUDE_PLUGIN_ROOT}/bin/srv", "args": ["--x"]},
                               "other": {"command": "/bin/different"}}}"#,
        );
        let found = run_with(&m, &plugins);
        assert_eq!(
            shape(&found),
            [
                ("duplicate-mcp", "mine".into(), false),
                ("duplicate-mcp", "plugin:demo:srv".into(), true)
            ]
        );
        assert_eq!(
            found[1].detail,
            "runs 2 times; keep this copy (enabled plugin)"
        );
        assert_eq!(found[1].source, "/h/plug/demo/.mcp.json");
        assert_eq!(found[1].command, "/h/plug/demo/bin/srv --x");
        // A plugin's file is never ours to rewrite; the hand-written copy is (T331.6).
        assert_eq!(
            found.iter().map(|p| p.fixable).collect::<Vec<_>>(),
            [true, false]
        );
    }

    #[cfg(unix)]
    #[test]
    fn a_plugin_server_is_not_shadowed_by_a_same_name_entry_and_rtoks_own_is_skipped() {
        let mut m = Mock::default();
        user(&mut m, r#""srv": {"command": "/bin/a"}"#);
        let plugins = plugin(
            &mut m,
            "demo@mkt",
            r#"{"mcpServers": {"srv": {"command": "/bin/b"},
                               "rtok": {"command": "rtok", "args": ["mcp"]}}}"#,
        );
        assert!(run_with(&m, &plugins).is_empty());
        // Without the plugin in the enabled set its file is not read, whatever it holds.
        assert!(run_with(&m, &[]).is_empty());
        user(&mut m, r#""srv": {"command": "/bin/b"}"#);
        assert_eq!(run_with(&m, &plugins).len(), 2);
        assert!(run_with(&m, &[]).is_empty());
    }

    #[cfg(unix)]
    #[test]
    fn two_plugins_providing_one_launch_keep_the_first_and_a_missing_file_is_skipped() {
        let mut m = Mock::default();
        let mut plugins = plugin(
            &mut m,
            "a@mkt",
            r#"{"mcpServers": {"s": {"command": "npx", "args": ["-y", "pkg@1"]}}}"#,
        );
        plugins.extend(plugin(
            &mut m,
            "b@mkt",
            r#"{"mcpServers": {"s": {"command": "npx", "args": ["-y", "pkg@1"]}}}"#,
        ));
        plugins.push(("c@mkt".into(), "/h/plug/c".into()));
        let found = run_with(&m, &plugins);
        assert_eq!(
            shape(&found),
            [
                ("duplicate-mcp", "plugin:a:s".into(), true),
                ("duplicate-mcp", "plugin:b:s".into(), false)
            ]
        );
    }

    #[cfg(unix)]
    #[test]
    fn a_toml_host_and_an_unparsable_file_are_handled() {
        let mut m = Mock::default();
        m.files.insert(
            "/h/.codex/config.toml".into(),
            "[mcp_servers.a]\ncommand = \"/bin/t\"\nargs = [\"1\"]\n[mcp_servers.b]\ncommand = \"/bin/t\"\nargs = [\"1\"]\n".into(),
        );
        m.files.insert("/h/.claude.json".into(), "{ nope".into());
        let found = run(&m);
        assert_eq!(found.len(), 2);
        assert_eq!(found[0].agent, "codex");
    }

    #[test]
    fn the_text_says_none_found_or_lists_each_group_once() {
        assert_eq!(
            crate::doctor::dupes::render_mcp(&[]),
            "duplicate mcp servers none found\n"
        );
    }
}
