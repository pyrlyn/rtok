// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! The Doctor tab's `doctor --fix` checklist (T478, D27): the counterpart of the web page's
//! "Fix selected" panel. It holds no rules of its own: the plan comes from
//! [`web::plan_here`] and the write from [`web::apply_here`], the pair the web page calls, so
//! the selection semantics, the backup, the re-read and the refusals are the CLI's. The state is
//! only the user's changes since the defaults ([`Selection`]), and every key that changes them
//! asks the engine for a new plan, so a write never acts on a stale list.

use crossterm::event::KeyCode;
use ratatui::style::Style;
use ratatui::text::Line;
use ratatui::widgets::Paragraph;

use super::theme::{self, ERR, OK, WARN};
use crate::config::Config;
use crate::doctor::web::{self, Fixed, Plan, Ref, Selection};

/// The plan and the write, injected so tests run them on an in-memory machine instead of the
/// creator's real host configs.
type PlanFn = Box<dyn Fn(&Config, &Selection) -> Plan>;
type ApplyFn = Box<dyn Fn(&Config, &Selection) -> Fixed>;

pub(super) struct Engine {
    plan: PlanFn,
    apply: ApplyFn,
}

impl Engine {
    pub(super) fn machine() -> Self {
        Self {
            plan: Box::new(web::plan_here),
            apply: Box::new(web::apply_here),
        }
    }

    #[cfg(test)]
    pub(super) fn new(
        plan: impl Fn(&Config, &Selection) -> Plan + 'static,
        apply: impl Fn(&Config, &Selection) -> Fixed + 'static,
    ) -> Self {
        Self {
            plan: Box::new(plan),
            apply: Box::new(apply),
        }
    }
}

/// What a key did to the panel.
#[derive(Debug, PartialEq, Eq)]
pub(super) enum Keyed {
    /// Not the panel's key: the shell handles it.
    Pass,
    Taken,
    /// A host file may have changed: the caller re-reads the model.
    Wrote,
}

pub(super) struct DoctorFix {
    engine: Engine,
    open: bool,
    sel: Selection,
    plan: Option<Plan>,
    cursor: usize,
    /// The apply key was pressed once; the next key decides.
    confirm: bool,
    done: Option<Fixed>,
}

impl DoctorFix {
    pub(super) fn new(engine: Engine) -> Self {
        Self {
            engine,
            open: false,
            sel: Selection::default(),
            plan: None,
            cursor: 0,
            confirm: false,
            done: None,
        }
    }

    pub(super) fn key(&mut self, code: KeyCode, cfg: &Config) -> Keyed {
        if !self.open {
            if code != KeyCode::Char('f') {
                return Keyed::Pass;
            }
            // A fresh checklist each time: the last one's toggles describe a list that may be gone.
            (self.sel, self.plan, self.cursor) = (Selection::default(), None, 0);
            (self.confirm, self.done, self.open) = (false, None, true);
            self.replan(cfg);
            return Keyed::Taken;
        }
        if self.done.is_some() {
            self.open = false;
            return Keyed::Taken;
        }
        if self.confirm {
            // Only an explicit `y` writes; every other key declines and is spent doing so.
            self.confirm = false;
            return if code == KeyCode::Char('y') {
                self.done = Some((self.engine.apply)(cfg, &self.sel));
                self.plan = None;
                Keyed::Wrote
            } else {
                Keyed::Taken
            };
        }
        match code {
            KeyCode::Esc => self.open = false,
            KeyCode::Up => self.cursor = self.cursor.saturating_sub(1),
            KeyCode::Down => self.cursor = (self.cursor + 1).min(self.last()),
            KeyCode::Char(' ') => self.toggle(cfg),
            KeyCode::Char('k') => self.keep(cfg),
            KeyCode::Enter => self.confirm = self.chosen() > 0,
            _ => return Keyed::Pass,
        }
        Keyed::Taken
    }

    fn replan(&mut self, cfg: &Config) {
        self.plan = Some((self.engine.plan)(cfg, &self.sel));
        self.cursor = self.cursor.min(self.last());
    }

    fn last(&self) -> usize {
        let n = self.plan.as_ref().map_or(0, |p| p.items.len());
        n.saturating_sub(1)
    }

    fn chosen(&self) -> usize {
        let items = self.plan.iter().flat_map(|p| &p.items);
        items.filter(|i| i.selected).count()
    }

    fn current(&self) -> Option<(Ref, bool)> {
        let i = self.plan.as_ref()?.items.get(self.cursor)?;
        let r = Ref {
            source: i.source.clone(),
            path: i.path.clone(),
        };
        Some((r, i.can_keep))
    }

    fn toggle(&mut self, cfg: &Config) {
        let Some((r, _)) = self.current() else { return };
        let same = |x: &Ref| x.source == r.source && x.path == r.path;
        match self.sel.toggled.iter().position(same) {
            Some(i) => {
                self.sel.toggled.remove(i);
            }
            None => self.sel.toggled.push(r),
        }
        self.replan(cfg);
    }

    fn keep(&mut self, cfg: &Config) {
        let Some((r, true)) = self.current() else {
            return;
        };
        // The defaults move with the kept copy, so old toggles no longer mean what they did.
        self.sel.keep.push(r);
        self.sel.toggled.clear();
        self.replan(cfg);
    }

    /// The panel that replaces the report while the checklist is open.
    pub(super) fn paragraph(&self) -> Option<Paragraph<'static>> {
        if !self.open {
            return None;
        }
        let bold = Style::new().bold();
        let mut lines = Vec::new();
        if let Some(done) = &self.done {
            let (head, color) = match done.code {
                0 => ("doctor --fix: done (any key closes)", OK),
                _ => ("doctor --fix: some entries were skipped or failed", WARN),
            };
            lines.push(Line::styled(head, Style::new().fg(color).bold()));
            lines.extend(done.text.lines().map(|l| Line::from(l.to_owned())));
        } else if let Some(plan) = &self.plan {
            lines.push(if self.confirm {
                let n = self.chosen();
                let msg = format!("Remove {n} selected entries? y = apply, any other key = cancel");
                Line::styled(msg, Style::new().fg(WARN).bold())
            } else {
                Line::styled("doctor --fix checklist (dry run, nothing written)", bold)
            });
            self.item_lines(plan, &mut lines);
            lines.extend(
                plan.refused
                    .iter()
                    .map(|r| Line::styled(r.clone(), theme::muted())),
            );
            lines.push(Line::default());
            lines.extend(plan.diff.lines().map(diff_line));
        }
        Some(Paragraph::new(lines))
    }

    fn item_lines(&self, plan: &Plan, lines: &mut Vec<Line<'static>>) {
        if plan.items.is_empty() {
            lines.push(Line::styled("nothing to remove", theme::muted()));
        }
        for (n, i) in plan.items.iter().enumerate() {
            let mark = if i.selected { 'x' } else { ' ' };
            let mut text = format!("[{mark}] {} {} in {}", i.kind, i.label, i.source);
            if i.shared {
                text.push_str(" (project file)");
            }
            if let Some(kept) = &i.kept_in {
                text.push_str(&format!(" (keeps {kept})"));
            }
            let style = if n == self.cursor {
                theme::selected()
            } else {
                Style::new()
            };
            lines.push(Line::styled(text, style));
            lines.push(Line::styled(format!("      {}", i.detail), theme::muted()));
        }
    }
}

fn diff_line(l: &str) -> Line<'static> {
    let color = match l.chars().next() {
        Some('+') => OK,
        Some('-') => ERR,
        _ => theme::MUTED,
    };
    Line::styled(l.to_owned(), Style::new().fg(color))
}

#[cfg(test)]
pub(super) mod tests {
    use std::rc::Rc;

    use super::*;
    use crate::doctor::fix::tests::{
        BROKEN, Machine, PROJECT_DUP, SETTINGS, USER_DUP, cfg, machine, put, text,
    };
    use crate::doctor::fix::{KINDS, fix_for};
    use crate::doctor::hooks::Probes;

    pub(in crate::tui) const PROJECT: &str = "/proj/.claude/settings.json";

    fn probes(m: &Machine) -> Probes<'_> {
        Probes {
            fs: m,
            env: m,
            which: m,
        }
    }

    /// The engine over an in-memory machine: no real host file is ever read or written.
    pub(in crate::tui) fn engine_on(m: &Rc<Machine>) -> Engine {
        let (a, b) = (Rc::clone(m), Rc::clone(m));
        Engine::new(
            move |_, sel| web_plan(&a, sel),
            move |_, sel| web::apply(&cfg(), &probes(&b), &*b, &web::default_opts(&cfg()), sel),
        )
    }

    fn web_plan(m: &Machine, sel: &Selection) -> Plan {
        web::plan(&cfg(), &probes(m), m, &web::default_opts(&cfg()), sel)
    }

    fn at(source: &str) -> Ref {
        Ref {
            source: source.into(),
            path: "hooks.Stop[0].hooks[0]".into(),
        }
    }

    fn json<T: serde::Serialize>(t: &T) -> String {
        serde_json::to_string(t).expect("serializes")
    }

    fn send(f: &mut DoctorFix, keys: &[KeyCode]) -> Vec<Keyed> {
        keys.iter().map(|k| f.key(*k, &Config::default())).collect()
    }

    /// The cursor onto the item of `source`.
    fn go_to(f: &mut DoctorFix, source: &str) {
        let i = f.plan.as_ref().unwrap().items.iter();
        let n = i.into_iter().position(|x| x.source == source).unwrap();
        send(f, &vec![KeyCode::Down; n]);
    }

    fn fresh(settings: &str, project: Option<&str>) -> (Rc<Machine>, DoctorFix) {
        let m = Rc::new(machine(settings));
        if let Some(p) = project {
            put(&m, PROJECT, p);
        }
        let f = DoctorFix::new(engine_on(&m));
        (m, f)
    }

    #[cfg(unix)] // POSIX hook paths
    #[test]
    fn the_plan_is_the_webs_plan_for_the_same_selection_and_writes_nothing() {
        let (m, mut f) = fresh(BROKEN, Some(BROKEN));
        assert_eq!(send(&mut f, &[KeyCode::Char('f')]), [Keyed::Taken]);
        let first = web_plan(&m, &Selection::default());
        assert_eq!(json(f.plan.as_ref().unwrap()), json(&first));
        // The shared project file starts unselected; Space selects it.
        go_to(&mut f, PROJECT);
        send(&mut f, &[KeyCode::Char(' ')]);
        let sel = Selection {
            keep: vec![],
            toggled: vec![at(PROJECT)],
        };
        assert_eq!(json(f.plan.as_ref().unwrap()), json(&web_plan(&m, &sel)));
        assert_eq!(f.chosen(), 2);
        send(&mut f, &[KeyCode::Char(' ')]);
        assert_eq!(json(f.plan.as_ref().unwrap()), json(&first));
        assert!(m.backups.borrow().is_empty());
        assert_eq!(
            (text(&m, SETTINGS), text(&m, PROJECT)),
            (BROKEN.into(), BROKEN.into())
        );
    }

    #[cfg(unix)]
    #[test]
    fn only_y_after_the_ask_writes_and_every_other_key_declines() {
        for decline in [
            KeyCode::Char('n'),
            KeyCode::Esc,
            KeyCode::Enter,
            KeyCode::Char('q'),
        ] {
            let (m, mut f) = fresh(BROKEN, None);
            send(&mut f, &[KeyCode::Char('f'), KeyCode::Enter]);
            assert!(f.confirm);
            assert_eq!(send(&mut f, &[decline]), [Keyed::Taken]);
            assert!(!f.confirm && f.done.is_none());
            // `y` without a fresh ask is not a confirm.
            assert_eq!(send(&mut f, &[KeyCode::Char('y')]), [Keyed::Pass]);
            assert_eq!(text(&m, SETTINGS), BROKEN, "{decline:?}");
            assert!(m.backups.borrow().is_empty());
        }
        // Nothing chosen: Enter does not even ask.
        let (_, mut f) = fresh("{}", None);
        send(&mut f, &[KeyCode::Char('f'), KeyCode::Enter]);
        assert!(!f.confirm);
    }

    #[cfg(unix)]
    #[test]
    fn apply_writes_what_doctor_fix_writes_and_leaves_the_unselected_file_alone() {
        let (m, mut f) = fresh(BROKEN, Some(BROKEN));
        let keys = [KeyCode::Char('f'), KeyCode::Enter, KeyCode::Char('y')];
        assert_eq!(send(&mut f, &keys)[2], Keyed::Wrote);
        let done = f.done.as_ref().expect("the result shows");
        assert_eq!(done.code, 0, "{}", done.text);
        assert!(done.text.contains("1 entry removed"), "{}", done.text);
        let twin = machine(BROKEN);
        fix_for(&cfg(), &probes(&twin), &twin, true, 3, None, &KINDS);
        assert_eq!(text(&m, SETTINGS), text(&twin, SETTINGS));
        assert!(!text(&m, SETTINGS).contains("old.sh"));
        assert_eq!(text(&m, PROJECT), BROKEN);
        assert_eq!(m.backups.borrow().len(), 1);
        // Any key closes the result and hands the keys back to the shell afterwards.
        assert_eq!(send(&mut f, &[KeyCode::Char('x')]), [Keyed::Taken]);
        assert!(f.paragraph().is_none());
        assert_eq!(send(&mut f, &[KeyCode::Char('x')]), [Keyed::Pass]);
    }

    #[cfg(unix)]
    #[test]
    fn keeping_the_other_copy_moves_the_removal_and_only_our_entry_changes() {
        let (m, mut f) = fresh(USER_DUP, Some(PROJECT_DUP));
        send(&mut f, &[KeyCode::Char('f'), KeyCode::Char('k')]);
        go_to(&mut f, PROJECT);
        send(
            &mut f,
            &[KeyCode::Char(' '), KeyCode::Enter, KeyCode::Char('y')],
        );
        let sel = Selection {
            keep: vec![at(SETTINGS)],
            toggled: vec![at(PROJECT)],
        };
        let twin = machine(USER_DUP);
        put(&twin, PROJECT, PROJECT_DUP);
        web::apply(
            &cfg(),
            &probes(&twin),
            &twin,
            &web::default_opts(&cfg()),
            &sel,
        );
        assert_eq!(text(&m, PROJECT), text(&twin, PROJECT));
        // The user's file, comment included, is untouched.
        assert_eq!(text(&m, SETTINGS), USER_DUP);
        assert!(!text(&m, PROJECT).contains("Stop"));
    }

    #[cfg(unix)]
    #[test]
    fn the_panel_lists_the_items_and_asks_before_it_writes() {
        let (_, mut f) = fresh(BROKEN, None);
        assert!(f.paragraph().is_none());
        send(&mut f, &[KeyCode::Char('f')]);
        let shown = format!("{:?}", f.paragraph().unwrap());
        assert!(
            shown.contains("broken-hook") && shown.contains("old.sh"),
            "{shown}"
        );
        assert!(shown.contains("nothing written"), "{shown}");
        send(&mut f, &[KeyCode::Enter]);
        let asking = format!("{:?}", f.paragraph().unwrap());
        assert!(
            asking.contains("Remove 1 selected entries? y = apply"),
            "{asking}"
        );
    }
}
