// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! The Hosts tab's "clear safe junk" panel (T479, D27): the counterpart of the web page's
//! button. It holds no rules of its own: the dry run is [`junk_web::plan`] and the removal is
//! [`junk_web::apply`], the pair the web page calls, so the reach (the `safe` kinds), the
//! re-check before each delete and the refusals are `agents junk clear --yes`'s. The text shown
//! is that command's own [`junk_clear::to_text`].

use crossterm::event::KeyCode;
use ratatui::style::Style;
use ratatui::text::Line;
use ratatui::widgets::Paragraph;

use super::doctor_fix::Keyed;
use super::theme::{self, OK, WARN};
use crate::agents::junk::{self, Report};
use crate::agents::junk_clear::{self, Cleared, Planned};
use crate::agents::junk_items::View;
use crate::agents::junk_web;
use crate::config::Config;

/// The scan, injected so tests read a fixture home instead of the creator's real one.
type ReportFn = Box<dyn Fn(&Config) -> Report>;

pub(super) struct Engine(ReportFn);

impl Engine {
    pub(super) fn machine() -> Self {
        Self(Box::new(junk::report))
    }

    #[cfg(test)]
    pub(super) fn new(report: impl Fn(&Config) -> Report + 'static) -> Self {
        Self(Box::new(report))
    }
}

enum Stage {
    Closed,
    /// The dry run is on screen; `y` removes, any other key declines.
    Plan(Cleared),
    /// The removal ran; any key closes.
    Done(Cleared),
}

pub(super) struct JunkClear {
    engine: Engine,
    stage: Stage,
}

impl JunkClear {
    pub(super) fn new(engine: Engine) -> Self {
        Self {
            engine,
            stage: Stage::Closed,
        }
    }

    pub(super) fn key(&mut self, code: KeyCode, cfg: &Config) -> Keyed {
        match std::mem::replace(&mut self.stage, Stage::Closed) {
            Stage::Closed if code == KeyCode::Char('c') => {
                let report = (self.engine.0)(cfg);
                self.stage = Stage::Plan(junk_web::plan(cfg, &report));
                Keyed::Taken
            }
            Stage::Closed => Keyed::Pass,
            Stage::Plan(plan) if code == KeyCode::Char('y') => {
                let paths: Vec<String> = removable(&plan).map(|p| p.path.clone()).collect();
                if paths.is_empty() {
                    return Keyed::Taken;
                }
                // A fresh scan: `apply` plans again and cuts it to what the user was shown, so
                // an item that appeared since the dry run is never removed unseen.
                let report = (self.engine.0)(cfg);
                let cleared = junk_web::apply(cfg, &report, &paths);
                crate::model::forget_junk();
                self.stage = Stage::Done(cleared);
                Keyed::Wrote
            }
            Stage::Plan(_) | Stage::Done(_) => Keyed::Taken,
        }
    }

    /// The panel that replaces the Hosts report while the plan or the result is up.
    pub(super) fn paragraph(&self, cfg: &Config) -> Option<Paragraph<'static>> {
        let (cleared, head, color) = match &self.stage {
            Stage::Closed => return None,
            Stage::Plan(c) if removable(c).next().is_none() => (
                c,
                "clear safe junk: nothing to remove (any key closes)",
                theme::MUTED,
            ),
            Stage::Plan(c) => (
                c,
                "clear safe junk (dry run, nothing removed): y = remove, any other key = cancel",
                WARN,
            ),
            Stage::Done(c) if c.failed() => (
                c,
                "clear safe junk: some items were kept (any key closes)",
                WARN,
            ),
            Stage::Done(c) => (c, "clear safe junk: done (any key closes)", OK),
        };
        let mut lines = vec![Line::styled(head, Style::new().fg(color).bold())];
        let text = junk_clear::to_text(cleared, &View::new(cfg));
        lines.extend(text.lines().map(|l| Line::from(l.to_owned())));
        Some(Paragraph::new(lines))
    }
}

/// What `y` removes: the planned items the plan marks `clear`.
fn removable(c: &Cleared) -> impl Iterator<Item = &Planned> {
    c.items.iter().filter(|p| p.planned && p.action == "clear")
}

#[cfg(test)]
mod tests {
    use std::collections::BTreeMap;
    use std::path::{Path, PathBuf};

    use crossterm::event::KeyModifiers;

    use super::*;
    use crate::agents::HOSTS;
    use crate::agents::junk::{AGENT_SCAN_LIMIT, Options, report_with};
    use crate::agents::junk_cache::age_files;
    use crate::agents::junk_clear::Filter;
    use crate::agents::junk_map::Roots;
    use crate::tui::app::App;
    use crate::tui::view::tests::screen;

    const DAY: u64 = 86_400;

    fn put(path: &Path, n: usize) {
        std::fs::create_dir_all(path.parent().unwrap()).unwrap();
        std::fs::write(path, vec![b'x'; n]).unwrap();
    }

    /// A fixture home: a stale rtok log generation (safe), a Claude debug log (a `review` kind,
    /// out of the panel's reach) and the host's own settings. Aged past the one-minute settle.
    fn fixture(tag: &str) -> (Config, PathBuf, Engine) {
        let (mut cfg, dir) = crate::testutil::config(tag);
        cfg.log.files = 1;
        put(&dir.join("rtok.log.7"), 5);
        put(&dir.join(".claude/debug/a.log"), 100);
        put(&dir.join(".claude/settings.json"), 10);
        age_files(&dir, 2 * DAY);
        let home = dir.clone();
        let engine = Engine::new(move |cfg| {
            let roots = Roots::new(home.clone(), |_| None);
            let opts = Options {
                all: true,
                cwd: std::env::temp_dir(),
                ..Options::default()
            };
            report_with(cfg, &roots, opts, AGENT_SCAN_LIMIT)
        });
        (cfg, dir, engine)
    }

    /// Every file under `dir` with its length and mtime: "nothing changed" is this equal. The
    /// ledger's SQLite files are left out: the App opens them on its own ticks.
    fn tree(dir: &Path) -> BTreeMap<PathBuf, (u64, std::time::SystemTime)> {
        let mut out = BTreeMap::new();
        for e in std::fs::read_dir(dir).unwrap().flatten() {
            // Named before stat: a `-wal` file may be gone by the time it is read.
            if e.file_name().to_string_lossy().starts_with("rtok.db") {
                continue;
            }
            let meta = e.metadata().unwrap();
            if meta.is_dir() {
                out.extend(tree(&e.path()));
            } else {
                out.insert(e.path(), (meta.len(), meta.modified().unwrap()));
            }
        }
        out
    }

    fn app_on(tag: &str) -> (App, PathBuf) {
        let (cfg, dir, engine) = fixture(tag);
        let mut app = App::new(&cfg);
        app.set_junk_engine(engine);
        while app.page() != "hosts" {
            app.key(KeyCode::Right, KeyModifiers::NONE);
        }
        (app, dir)
    }

    fn press(app: &mut App, code: KeyCode) -> bool {
        app.key(code, KeyModifiers::NONE)
    }

    #[test]
    fn the_plan_is_the_clear_dry_run_and_declining_changes_no_file() {
        let (mut app, dir) = app_on("tui-junk-decline");
        let before = tree(&dir);

        assert!(!press(&mut app, KeyCode::Char('c')));
        let shown = screen(&app);
        assert!(shown.contains("dry run"), "{shown}");
        assert!(shown.contains("rtok.log.7"), "{shown}");
        assert!(
            !shown.contains("a.log"),
            "a review kind was offered: {shown}"
        );

        // `agents junk clear` with every agent named and nothing else, as the CLI parses it.
        let (cfg, _, engine) = fixture("tui-junk-decline-cli");
        let cli = Filter {
            agents: ["rtok"]
                .into_iter()
                .chain(HOSTS.iter().copied())
                .map(String::from)
                .collect(),
            ..Filter::default()
        };
        let report = (engine.0)(&cfg);
        let dry = junk_clear::run_in(&cfg, &report, &cli, false, None);
        let mine = junk_web::plan(&cfg, &report);
        let paths = |c: &Cleared| {
            c.items
                .iter()
                .map(|p| (p.kind, p.bytes, p.planned))
                .collect::<Vec<_>>()
        };
        assert_eq!(paths(&mine), paths(&dry));
        assert_eq!(dry.planned_bytes, 5);

        for decline in [KeyCode::Char('n'), KeyCode::Esc, KeyCode::Char('q')] {
            press(&mut app, KeyCode::Char('c'));
            assert!(
                !press(&mut app, decline),
                "{decline:?} declines, it does not quit"
            );
            assert!(app.junk().paragraph(app.cfg()).is_none());
            assert_eq!(tree(&dir), before, "{decline:?} changed a file");
        }
        assert!(press(&mut app, KeyCode::Esc), "the next Esc quits");
    }

    #[test]
    fn confirming_removes_exactly_the_safe_items_and_reports_freed_of_planned() {
        let (mut app, dir) = app_on("tui-junk-confirm");
        let mut expect = tree(&dir);
        expect.remove(&dir.join("rtok.log.7"));

        press(&mut app, KeyCode::Char('c'));
        assert!(!press(&mut app, KeyCode::Char('y')));
        let shown = screen(&app);
        assert!(shown.contains("Freed 5 B of 5 B planned"), "{shown}");
        assert_eq!(tree(&dir), expect, "only the safe item went");
        assert!(dir.join(".claude/debug/a.log").exists());

        press(&mut app, KeyCode::Char('x'));
        assert!(
            app.junk().paragraph(app.cfg()).is_none(),
            "any key closes the result"
        );
    }

    #[test]
    fn y_without_a_plan_removes_nothing() {
        let (mut app, dir) = app_on("tui-junk-no-plan");
        let before = tree(&dir);
        press(&mut app, KeyCode::Char('y'));
        assert!(app.junk().paragraph(app.cfg()).is_none());
        assert_eq!(tree(&dir), before);
    }
}
