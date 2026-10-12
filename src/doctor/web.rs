// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! The web doctor page's side of `rtok doctor --fix` (T331.12). It is stateless: every request
//! carries the user's choices as changes to the defaults the terminal checklist starts from (the
//! kept-copy swaps and the toggled entries), and the server rebuilds the findings, so a request
//! never acts on a stale list. `plan` returns the items, the per-file diff and the evidence of
//! what it showed ([`Confirmed`]) and writes nothing; `apply` writes through `fix_bound`, with its
//! backup, re-read and refusals. When the request carries that evidence, a file or an entry that is
//! not exactly what the preview showed is refused as stale and a fresh plan comes back instead
//! (T576.1), so a finding that appeared after the preview is never written on its confirmation;
//! a request without the evidence is refused outright (T576.2).

use std::collections::BTreeSet;

use schemars::JsonSchema;
use serde::{Deserialize, Serialize};

use super::checklist::{self, Key};
use super::fix::{self, Confirmed, Opts};
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
#[derive(Debug, Clone, Default, Deserialize, Serialize, JsonSchema)]
pub struct Selection {
    /// Entries made the kept copy of their duplicate.
    #[serde(default)]
    pub keep: Vec<Ref>,
    /// Entries whose selection is the opposite of their default.
    #[serde(default)]
    pub toggled: Vec<Ref>,
    /// The `confirmed` of the plan the user saw. A plan request leaves it out; an apply without it
    /// is refused, because a write that is not bound to a preview can touch what no one saw.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub confirmed: Option<Confirmed>,
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
    /// What this plan showed; send it back with the confirmation.
    pub confirmed: Confirmed,
}

#[derive(Debug, Serialize, JsonSchema)]
pub struct Fixed {
    pub text: String,
    /// 1 when a selected entry was skipped or failed.
    pub code: i32,
    /// Set when the files no longer matched the preview and nothing of it was written: the plan
    /// as it is now, to review and confirm again.
    #[serde(skip_serializing_if = "Option::is_none")]
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
    let (cwd, home) = (p.env.cwd(), p.env.home());
    let shared = checklist::shared_in(cwd.as_deref(), home.as_deref());
    let items = checklist::items(&found, o.kinds)
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
    let report = fix::fix_found(cfg, p, w, false, o, found, &off);
    Plan {
        items,
        diff: fix::diffs(&report),
        refused: fix::refusals(&report),
        confirmed: report.confirmed(),
    }
}

/// Write the selection. The caller sends this only for the page's explicit confirmation.
pub fn apply(cfg: &Config, p: &Probes, w: &dyn Writer, o: &Opts, sel: &Selection) -> Fixed {
    // The pages are embedded in this binary and always send the evidence, so a request without it
    // is a stale or foreign client; the unbound path stays for `doctor --fix`, which is no web.
    let Some(confirmed) = sel.confirmed.as_ref() else {
        return Fixed {
            text: "refused: this request carries no preview to bind the write to, so nothing was \
                   written; review the changes below and confirm them again\n"
                .into(),
            code: 1,
            replan: Some(plan(cfg, p, w, o, sel)),
        };
    };
    let (found, off) = prepare(cfg, p, o, sel);
    let report = fix::fix_bound(cfg, p, w, true, o, found, &off, Some(confirmed));
    // The new plan carries the user's deltas but not the old evidence, and selects what the
    // defaults select: the page shows it and asks again.
    let replan = report.stale().then(|| {
        let now = Selection {
            confirmed: None,
            ..sel.clone()
        };
        plan(cfg, p, w, o, &now)
    });
    Fixed {
        text: fix::render(&report, true),
        code: report.exit_code(),
        replan,
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
pub fn apply_here(cfg: &Config, sel: &Selection) -> Fixed {
    fix::on_this_machine(|p, w| apply(cfg, p, w, &default_opts(cfg), sel))
}

#[cfg(test)]
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
        let done = apply_confirmed(&m, &Selection::default());
        assert_eq!(done.code, 0, "{}", done.text);
        assert!(done.text.contains("1 entry removed"), "{}", done.text);
        assert!(!text(&m, SETTINGS).contains("old.sh"));
        assert_eq!(text(&m, PROJECT), BROKEN);
        assert_eq!(m.backups.borrow().len(), 1);
        // The toggle flips the project file on, and the user's off.
        let sel = Selection {
            toggled: vec![at(PROJECT)],
            ..Selection::default()
        };
        let m = machine(BROKEN);
        put(&m, PROJECT, BROKEN);
        apply_confirmed(&m, &sel);
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
            ..Selection::default()
        };
        let swapped = plan_of(&m, &sel);
        assert_eq!(swapped.items.len(), 1);
        assert!(swapped.items[0].shared && swapped.items[0].selected);
        assert_eq!(swapped.items[0].kept_in.as_deref(), Some(SETTINGS));
        apply_confirmed(&m, &sel);
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
            ..Selection::default()
        };
        let done = apply_confirmed(&m, &sel);
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
        let bound = Selection {
            confirmed: Some(plan.confirmed),
            ..Selection::default()
        };
        apply(&c, &probes(&m), &m, &opts(), &bound);
        assert_eq!(text(&m, "/h/.kimi-code/config.toml"), toml);
    }

    const LOCAL: &str = "/h/.claude/settings.local.json";
    const HOOK_B: &str = r#"{"hooks": {"PreToolUse": [{"hooks": [{"type": "command", "command": "/h/gone/b.sh"}]}]}}"#;
    const BOTH: &str = r#"{"hooks": {"Stop": [{"hooks": [{"type": "command", "command": "/h/gone/old.sh"}]}], "PreToolUse": [{"hooks": [{"type": "command", "command": "/h/gone/b.sh"}]}]}}"#;

    /// The bound apply of what `plan_of` showed, as the page sends it back.
    fn confirm(m: &Machine, sel: &Selection) -> (Selection, Plan) {
        let preview = plan_of(m, sel);
        let bound = Selection {
            confirmed: Some(preview.confirmed.clone()),
            ..sel.clone()
        };
        (bound, preview)
    }

    /// Preview and confirm in one step, for tests about what a confirmed write does.
    fn apply_confirmed(m: &Machine, sel: &Selection) -> Fixed {
        apply(&cfg(), &probes(m), m, &opts(), &confirm(m, sel).0)
    }

    #[cfg(unix)]
    #[test]
    fn an_apply_without_the_preview_evidence_is_refused_and_writes_nothing() {
        let m = machine(BROKEN);
        put(&m, PROJECT, BROKEN);
        let done = apply(&cfg(), &probes(&m), &m, &opts(), &Selection::default());
        assert_eq!(done.code, 1, "{}", done.text);
        assert!(done.text.contains("no preview"), "{}", done.text);
        assert_eq!(text(&m, SETTINGS), BROKEN);
        assert_eq!(text(&m, PROJECT), BROKEN);
        assert!(m.backups.borrow().is_empty());
        // The page gets the plan to review and confirm with its evidence.
        let fresh = done.replan.expect("a refused apply returns the plan");
        assert!(!fresh.confirmed.files.is_empty());
        let again = Selection {
            confirmed: Some(fresh.confirmed),
            ..Selection::default()
        };
        assert_eq!(apply(&cfg(), &probes(&m), &m, &opts(), &again).code, 0);
        assert!(!text(&m, SETTINGS).contains("old.sh"));
    }

    #[cfg(unix)]
    #[test]
    fn an_unchanged_preview_applies_as_confirmed() {
        let m = machine(BROKEN);
        let (sel, _) = confirm(&m, &Selection::default());
        let done = apply(&cfg(), &probes(&m), &m, &opts(), &sel);
        assert_eq!(done.code, 0, "{}", done.text);
        assert!(done.replan.is_none());
        assert!(!text(&m, SETTINGS).contains("old.sh"));
    }

    #[cfg(unix)]
    #[test]
    fn an_entry_added_after_the_preview_in_the_same_file_refuses_the_whole_file() {
        let m = machine(BROKEN);
        let (sel, _) = confirm(&m, &Selection::default());
        put(&m, SETTINGS, BOTH);
        let done = apply(&cfg(), &probes(&m), &m, &opts(), &sel);
        assert_eq!(text(&m, SETTINGS), BOTH, "{}", done.text);
        assert_eq!(done.code, 1);
        assert!(
            done.text.contains("changed since the preview"),
            "{}",
            done.text
        );
        assert!(m.backups.borrow().is_empty());
        // The page gets the plan as it is now, with both entries and fresh evidence.
        let fresh = done.replan.expect("a stale apply returns a fresh plan");
        assert_eq!(fresh.items.len(), 2);
        assert_ne!(Some(&fresh.confirmed), sel.confirmed.as_ref());
        // Confirming that plan writes it.
        let again = Selection {
            confirmed: Some(fresh.confirmed),
            ..Selection::default()
        };
        let done = apply(&cfg(), &probes(&m), &m, &opts(), &again);
        assert_eq!(done.code, 0, "{}", done.text);
        assert!(!text(&m, SETTINGS).contains("b.sh"));
    }

    #[cfg(unix)]
    #[test]
    fn a_file_found_after_the_preview_is_not_written_but_the_confirmed_one_is() {
        let m = machine(BROKEN);
        let (sel, _) = confirm(&m, &Selection::default());
        put(&m, LOCAL, HOOK_B);
        let done = apply(&cfg(), &probes(&m), &m, &opts(), &sel);
        assert_eq!(text(&m, LOCAL), HOOK_B, "{}", done.text);
        assert!(!text(&m, SETTINGS).contains("old.sh"));
        assert_eq!(done.code, 1);
        assert_eq!(done.replan.expect("stale").items.len(), 1);
    }

    #[cfg(unix)]
    #[test]
    fn a_file_that_changed_in_bytes_only_is_refused() {
        let m = machine(BROKEN);
        let (sel, _) = confirm(&m, &Selection::default());
        let edited = format!("{BROKEN}\n");
        put(&m, SETTINGS, &edited);
        let done = apply(&cfg(), &probes(&m), &m, &opts(), &sel);
        assert_eq!(text(&m, SETTINGS), edited, "{}", done.text);
        assert!(done.replan.is_some());
    }

    #[cfg(unix)]
    #[test]
    fn a_confirmed_entry_that_is_gone_or_replaced_writes_nothing() {
        let m = machine(BROKEN);
        let (sel, _) = confirm(&m, &Selection::default());
        // Fixed by hand: nothing is left to remove for the confirmed file.
        put(&m, SETTINGS, "{}");
        let done = apply(&cfg(), &probes(&m), &m, &opts(), &sel);
        assert_eq!(text(&m, SETTINGS), "{}");
        assert_eq!(done.code, 1);
        assert!(done.replan.is_some_and(|p| p.items.is_empty()));
        // Another command at the same key path is not the confirmed entry.
        put(&m, SETTINGS, &BROKEN.replace("old.sh", "other.sh"));
        let done = apply(&cfg(), &probes(&m), &m, &opts(), &sel);
        assert!(text(&m, SETTINGS).contains("other.sh"), "{}", done.text);
    }

    #[cfg(unix)]
    #[test]
    fn evidence_that_names_other_entries_than_the_findings_is_refused() {
        let m = machine(BROKEN);
        let (mut sel, _) = confirm(&m, &Selection::default());
        // Same bytes, but the preview claimed a different entry (the findings moved, as when a
        // duplicate's kept copy changes in another file).
        sel.confirmed.as_mut().unwrap().files[0].entries[0].command = "/h/other.sh".into();
        let done = apply(&cfg(), &probes(&m), &m, &opts(), &sel);
        assert_eq!(text(&m, SETTINGS), BROKEN, "{}", done.text);
        assert!(done.replan.is_some());
    }

    #[cfg(unix)]
    #[test]
    fn toggles_and_swaps_stay_bound_to_the_plan_they_were_confirmed_in() {
        let m = machine(USER_DUP);
        put(&m, PROJECT, PROJECT_DUP);
        let first = Selection {
            keep: vec![at(SETTINGS)],
            toggled: vec![at(PROJECT)],
            ..Selection::default()
        };
        let (sel, preview) = confirm(&m, &first);
        assert_eq!(preview.confirmed.files.len(), 1);
        assert_eq!(preview.confirmed.files[0].source, PROJECT);
        // The page confirmed the swap; the default plan's evidence names the other file.
        let (unswapped, _) = confirm(&m, &Selection::default());
        let wrong = Selection {
            confirmed: unswapped.confirmed,
            ..first
        };
        let done = apply(&cfg(), &probes(&m), &m, &opts(), &wrong);
        assert_eq!(text(&m, PROJECT), PROJECT_DUP, "{}", done.text);
        assert_eq!(text(&m, SETTINGS), USER_DUP);
        let done = apply(&cfg(), &probes(&m), &m, &opts(), &sel);
        assert_eq!(done.code, 0, "{}", done.text);
        assert!(!text(&m, PROJECT).contains("Stop"));
    }
}
