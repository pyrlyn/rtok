// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! The web doctor page's side of `rtok doctor --fix` (T331.12). It is stateless: every request
//! carries the user's choices as changes to the defaults the terminal checklist starts from (the
//! kept-copy swaps and the toggled entries), and the server rebuilds the findings, so a request
//! never acts on a stale list. `plan` returns the items and the per-file diff and writes nothing;
//! `apply` writes through `fix_found`, with its backup, re-read and refusals, and only the exact
//! preview the user confirmed: `plan` returns a [`Plan::token`] of the selected entries and their
//! diff, `apply` is given that token back and refuses when the rescan no longer yields it.

use std::collections::BTreeSet;

use schemars::JsonSchema;
use serde::{Deserialize, Serialize};

use super::checklist::{self, Key};
use super::fix::{self, Opts};
use super::hooks::{Probes, Problem};
use super::probe::Writer;
use crate::config::Config;

/// An entry as the page names it: the file and the key path inside it.
#[derive(Debug, Clone, Deserialize, Serialize, JsonSchema)]
pub struct Ref {
    pub source: String,
    pub path: String,
}

/// What the user changed since the defaults.
#[derive(Debug, Default, Deserialize, Serialize, JsonSchema)]
pub struct Selection {
    /// Entries made the kept copy of their duplicate.
    #[serde(default)]
    pub keep: Vec<Ref>,
    /// Entries whose selection is the opposite of their default.
    #[serde(default)]
    pub toggled: Vec<Ref>,
}

/// One line of the checklist.
#[derive(Debug, Serialize, JsonSchema)]
pub struct Item {
    pub source: String,
    pub path: String,
    pub kind: String,
    pub agent: String,
    /// What the entry runs or is called.
    pub label: String,
    pub detail: String,
    /// The file that holds the copy kept instead, for a duplicate.
    pub kept_in: Option<String>,
    /// In a project's shared file: starts unselected.
    pub shared: bool,
    pub selected: bool,
    /// Whether this copy may be made the kept one.
    pub can_keep: bool,
}

#[derive(Debug, Serialize, JsonSchema)]
pub struct Plan {
    pub items: Vec<Item>,
    /// The diff of every file the selection would change.
    pub diff: String,
    /// Selected entries the engine will not remove, with why.
    pub refused: Vec<String>,
    /// Names this preview (its selected entries and their diff): `apply` writes only when the
    /// token it is given is the one the fresh rescan produces.
    pub token: String,
}

#[derive(Debug, Serialize, JsonSchema)]
pub struct Fixed {
    pub text: String,
    /// 1 when a selected entry was skipped or failed.
    pub code: i32,
    /// Set when nothing was written because the files no longer match the confirmed preview:
    /// the fresh preview, which needs its own confirmation.
    pub replan: Option<Plan>,
}

fn is(x: &Problem, r: &Ref) -> bool {
    x.source == r.source && x.path == r.path
}

/// The findings with the kept-copy swaps applied, and the entries left out of the write.
fn prepare(cfg: &Config, p: &Probes, o: &Opts, sel: &Selection) -> (Vec<Problem>, BTreeSet<Key>) {
    let mut found = fix::candidates(cfg, p, o.agent);
    for r in &sel.keep {
        if let Some(i) = found.iter().position(|x| is(x, r)) {
            // A refused swap leaves the kept copy as it was; the page learns it from the plan.
            let copy = checklist::copy_of(&found, i);
            let _ = checklist::keep_copy(&mut found, copy);
        }
    }
    let (cwd, home) = (p.env.cwd(), p.env.home());
    let shared = checklist::shared_in(cwd.as_deref(), home.as_deref());
    let mut off = checklist::defaults(&found, o.kinds, &shared);
    for t in &sel.toggled {
        let k = (t.source.clone(), t.path.clone());
        if !off.remove(&k) {
            off.insert(k);
        }
    }
    (found, off)
}

/// The checklist and the diff of the selection; nothing is written.
pub fn plan(cfg: &Config, p: &Probes, w: &dyn Writer, o: &Opts, sel: &Selection) -> Plan {
    let (found, off) = prepare(cfg, p, o, sel);
    preview(cfg, p, w, o, found, &off)
}

/// The preview of `found` minus `off`: the one scan both `plan` and `apply` judge.
fn preview(
    cfg: &Config,
    p: &Probes,
    w: &dyn Writer,
    o: &Opts,
    found: Vec<Problem>,
    off: &BTreeSet<Key>,
) -> Plan {
    let (cwd, home) = (p.env.cwd(), p.env.home());
    let shared = checklist::shared_in(cwd.as_deref(), home.as_deref());
    let items: Vec<Item> = checklist::items(&found, o.kinds)
        .into_iter()
        .map(|i| {
            let x = &found[i];
            let kept_in = found
                .iter()
                .find(|k| k.group.is_some() && k.kind == x.kind && k.group == x.group && k.keep)
                .map(|k| k.source.clone());
            let mut probe = found.clone();
            let can_keep = checklist::keep_copy(&mut probe, checklist::copy_of(&found, i)).is_ok();
            Item {
                source: x.source.clone(),
                path: x.path.clone(),
                kind: x.kind.into(),
                agent: x.agent.into(),
                label: fix::label(x),
                detail: x.detail.clone(),
                kept_in,
                shared: shared(x),
                selected: !off.contains(&checklist::key(x)),
                can_keep,
            }
        })
        .collect();
    let report = fix::fix_found(cfg, p, w, false, o, found, off);
    let diff = fix::diffs(&report);
    Plan {
        token: token_of(&items, &diff),
        items,
        diff,
        refused: fix::refusals(&report),
    }
}

/// A digest of what a confirmation covers: the selected entries by file and key path, and the
/// diff they produce. An entry that appeared, moved or changed after the preview changes it.
fn token_of(items: &[Item], diff: &str) -> String {
    let mut text = String::new();
    for i in items.iter().filter(|i| i.selected) {
        text.extend([i.source.as_str(), "\0", i.path.as_str(), "\0"]);
    }
    text.extend(["\0", diff]);
    crate::store::hex_sha256(text.as_bytes())
}

/// Write the selection, if `confirmed` (the [`Plan::token`] the user saw) still names it. The
/// caller sends this only for the page's explicit confirmation. A rescan that yields another
/// preview writes nothing and returns that preview instead, so an entry that appeared since is
/// never removed on the strength of a confirmation that did not show it. The write then uses the
/// very findings that were compared, and `fix_file` still re-reads each file just before writing.
pub fn apply(
    cfg: &Config,
    p: &Probes,
    w: &dyn Writer,
    o: &Opts,
    sel: &Selection,
    confirmed: &str,
) -> Fixed {
    let (found, off) = prepare(cfg, p, o, sel);
    let now = preview(cfg, p, w, o, found.clone(), &off);
    if now.token != confirmed {
        return Fixed {
            text: "The files changed since the preview, so nothing was written. Review the new \
                   preview and confirm again.\n"
                .into(),
            code: 1,
            replan: Some(now),
        };
    }
    let report = fix::fix_found(cfg, p, w, true, o, found, &off);
    Fixed {
        text: fix::render(&report, true),
        code: report.exit_code(),
        replan: None,
    }
}

/// What every UI asks of the engine: all kinds, all hosts, the configured backup depth. The web
/// page and the TUI share it so neither can drift from `doctor --fix`.
pub fn default_opts(cfg: &Config) -> Opts<'static> {
    Opts {
        keep: cfg.setup.backup_files as usize,
        agent: None,
        kinds: &fix::KINDS,
    }
}

/// [`plan`] on this machine.
pub fn plan_here(cfg: &Config, sel: &Selection) -> Plan {
    fix::on_this_machine(|p, w| plan(cfg, p, w, &default_opts(cfg), sel))
}

/// [`apply`] on this machine.
pub fn apply_here(cfg: &Config, sel: &Selection, confirmed: &str) -> Fixed {
    fix::on_this_machine(|p, w| apply(cfg, p, w, &default_opts(cfg), sel, confirmed))
}

#[cfg(test)]
// T902: every `#[test]` below is unix-only (POSIX paths), so on Windows the shared helpers are unused.
#[cfg_attr(windows, allow(dead_code, unused_imports))]
mod tests {
    use super::*;
    use crate::doctor::fix::KINDS;
    use crate::doctor::fix::tests::{
        BROKEN, Machine, PROJECT_DUP, SETTINGS, USER_DUP, cfg, machine, put, text,
    };

    const PROJECT: &str = "/proj/.claude/settings.json";

    fn probes(m: &Machine) -> Probes<'_> {
        Probes {
            fs: m,
            env: m,
            which: m,
        }
    }

    fn opts() -> Opts<'static> {
        Opts {
            keep: 3,
            agent: None,
            kinds: &KINDS,
        }
    }

    fn at(source: &str) -> Ref {
        Ref {
            source: source.into(),
            path: "hooks.Stop[0].hooks[0]".into(),
        }
    }

    fn plan_of(m: &Machine, sel: &Selection) -> Plan {
        plan(&cfg(), &probes(m), m, &opts(), sel)
    }

    #[cfg(unix)] // POSIX hook paths
    #[test]
    fn the_plan_starts_from_the_terminal_defaults_and_writes_nothing() {
        let m = machine(BROKEN);
        put(&m, PROJECT, BROKEN);
        let plan = plan_of(&m, &Selection::default());
        let by = |s: &str| plan.items.iter().find(|i| i.source == s).unwrap();
        assert!(by(SETTINGS).selected && !by(SETTINGS).shared);
        assert!(!by(PROJECT).selected && by(PROJECT).shared);
        assert!(plan.diff.contains(SETTINGS) && !plan.diff.contains(PROJECT));
        assert!(m.backups.borrow().is_empty());
        assert_eq!(text(&m, SETTINGS), BROKEN);
    }

    #[cfg(unix)]
    #[test]
    fn apply_writes_the_selection_after_a_backup_and_only_that() {
        let m = machine(BROKEN);
        put(&m, PROJECT, BROKEN);
        let sel = Selection::default();
        let done = apply(&cfg(), &probes(&m), &m, &opts(), &sel, &confirm(&m, &sel));
        assert_eq!(done.code, 0, "{}", done.text);
        assert!(done.text.contains("1 entry removed"), "{}", done.text);
        assert!(!text(&m, SETTINGS).contains("old.sh"));
        assert_eq!(text(&m, PROJECT), BROKEN);
        assert_eq!(m.backups.borrow().len(), 1);
        // The toggle flips the project file on, and the user's off.
        let sel = Selection {
            keep: vec![],
            toggled: vec![at(PROJECT)],
        };
        let m = machine(BROKEN);
        put(&m, PROJECT, BROKEN);
        apply(&cfg(), &probes(&m), &m, &opts(), &sel, &confirm(&m, &sel));
        assert!(!text(&m, PROJECT).contains("old.sh"));
    }

    #[cfg(unix)]
    #[test]
    fn a_kept_copy_swap_and_a_toggle_move_the_removal_to_the_other_file() {
        let m = machine(USER_DUP);
        put(&m, PROJECT, PROJECT_DUP);
        let first = plan_of(&m, &Selection::default());
        assert_eq!(first.items.len(), 1);
        assert_eq!(first.items[0].source, SETTINGS);
        assert!(first.items[0].can_keep);
        assert_eq!(first.items[0].kept_in.as_deref(), Some(PROJECT));
        let sel = Selection {
            keep: vec![at(SETTINGS)],
            toggled: vec![at(PROJECT)],
        };
        let swapped = plan_of(&m, &sel);
        assert_eq!(swapped.items.len(), 1);
        assert!(swapped.items[0].shared && swapped.items[0].selected);
        assert_eq!(swapped.items[0].kept_in.as_deref(), Some(SETTINGS));
        apply(&cfg(), &probes(&m), &m, &opts(), &sel, &confirm(&m, &sel));
        assert!(text(&m, SETTINGS).contains("/h/.claude/settings.json"));
        assert!(!text(&m, PROJECT).contains("Stop"));
    }

    #[cfg(unix)]
    #[test]
    fn a_ref_that_names_nothing_changes_nothing() {
        let m = machine(BROKEN);
        let sel = Selection {
            keep: vec![at("/nowhere")],
            toggled: vec![at("/nowhere")],
        };
        let done = apply(&cfg(), &probes(&m), &m, &opts(), &sel, &confirm(&m, &sel));
        assert!(done.text.contains("1 entry removed"), "{}", done.text);
        assert!(!text(&m, SETTINGS).contains("old.sh"));
    }

    #[cfg(unix)]
    #[test]
    fn a_toml_hook_is_refused_in_the_plan_and_never_written() {
        let toml = "[[hooks]]\nevent = \"Stop\"\ncommand = \"/h/gone.sh\"\n";
        let m = machine("{}");
        put(&m, "/h/.kimi-code/config.toml", toml);
        let mut c = cfg();
        c.setup.kimi.config_path = "/h/.kimi-code/config.toml".into();
        let plan = plan(&c, &probes(&m), &m, &opts(), &Selection::default());
        assert!(plan.items.is_empty(), "{:?}", plan.items);
        assert!(plan.refused[0].contains("TOML hook files are not edited yet"));
        let sel = Selection::default();
        apply(&c, &probes(&m), &m, &opts(), &sel, &plan.token);
        assert_eq!(text(&m, "/h/.kimi-code/config.toml"), toml);
    }

    const TWO: &str = r#"{"hooks": {"Stop": [{"hooks": [{"type": "command", "command": "/h/gone/old.sh"}]}, {"hooks": [{"type": "command", "command": "/h/gone/other.sh"}]}]}}"#;
    const BEFORE: &str = r#"{"hooks": {"Stop": [{"hooks": [{"type": "command", "command": "/h/gone/other.sh"}]}, {"hooks": [{"type": "command", "command": "/h/gone/old.sh"}]}]}}"#;
    const CURSOR: &str = "/h/.cursor/hooks.json";
    const CURSOR_BODY: &str =
        r#"{"version": 1, "hooks": {"stop": [{"command": "/h/gone/cursor.sh"}]}}"#;

    fn confirm(m: &Machine, sel: &Selection) -> String {
        plan_of(m, sel).token
    }

    #[cfg(unix)]
    #[test]
    fn an_entry_that_appeared_after_the_preview_is_not_removed_by_its_confirmation() {
        for later in [Later::SameFile, Later::BeforeIt, Later::NewFile] {
            let m = machine(BROKEN);
            let sel = Selection::default();
            let token = confirm(&m, &sel);
            let (path, body) = match later {
                Later::SameFile => (SETTINGS, TWO),
                Later::BeforeIt => (SETTINGS, BEFORE),
                Later::NewFile => (CURSOR, CURSOR_BODY),
            };
            put(&m, path, body);
            let done = apply(&cfg(), &probes(&m), &m, &opts(), &sel, &token);
            assert_eq!(done.code, 1, "{later:?}: {}", done.text);
            let fresh = done.replan.expect("a fresh preview to confirm");
            assert_ne!(fresh.token, token, "{later:?}");
            assert_eq!(text(&m, path), body, "{later:?}: nothing was written");
            assert!(m.backups.borrow().is_empty(), "{later:?}");
            // The fresh preview, once confirmed, writes what it shows and nothing else.
            let again = apply(&cfg(), &probes(&m), &m, &opts(), &sel, &fresh.token);
            assert_eq!(again.code, 0, "{later:?}: {}", again.text);
        }
    }

    #[derive(Debug, Clone, Copy)]
    enum Later {
        SameFile,
        BeforeIt,
        NewFile,
    }

    #[cfg(unix)]
    #[test]
    fn an_unchanged_preview_still_applies_and_a_missing_or_wrong_token_does_not() {
        let m = machine(BROKEN);
        let sel = Selection::default();
        for bad in ["", "0000"] {
            let done = apply(&cfg(), &probes(&m), &m, &opts(), &sel, bad);
            assert_eq!((done.code, done.replan.is_some()), (1, true), "{bad:?}");
            assert_eq!(text(&m, SETTINGS), BROKEN);
        }
        let done = apply(&cfg(), &probes(&m), &m, &opts(), &sel, &confirm(&m, &sel));
        assert_eq!(done.code, 0, "{}", done.text);
        assert!(!text(&m, SETTINGS).contains("old.sh"));
    }

    #[cfg(unix)]
    #[test]
    fn the_token_follows_the_selection_so_a_toggle_needs_its_own_confirmation() {
        let m = machine(BROKEN);
        put(&m, PROJECT, BROKEN);
        let first = Selection::default();
        let both = Selection {
            keep: vec![],
            toggled: vec![at(PROJECT)],
        };
        assert_ne!(confirm(&m, &first), confirm(&m, &both));
        let done = apply(
            &cfg(),
            &probes(&m),
            &m,
            &opts(),
            &both,
            &confirm(&m, &first),
        );
        assert_eq!(done.code, 1);
        assert_eq!(text(&m, PROJECT), BROKEN);
    }
}
