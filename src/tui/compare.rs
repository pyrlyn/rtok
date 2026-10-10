// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! The Graph page's compare view (T485, D27): the terminal counterpart of the web page's Compare
//! mode (T329.35). It computes nothing: the answer is [`diff::page_of`], the function the page's
//! `diff` request and `rtok graph diff --json` already run, so the counts, the callers and the
//! row cap are theirs. This module is the typed input, the background run and the rendering of the
//! typed report with the web panel's wording and marks (`+ − ~ →`), so colour is never the only
//! cue. The panel replaces the page body while it is open, as the doctor checklist does; the
//! stage, the keys and the layout are [`super::panel`]'s.

use crossterm::event::KeyCode;
use ratatui::text::Line;

use super::panel::{self, Answer, Panel, Stage, error_lines};
use super::theme;
use crate::config::Config;

pub(super) type Compare = Panel<Typed>;

#[derive(Default)]
pub(super) struct Typed {
    input: String,
    /// The registry id handed to the diff, and the name the panel calls it by.
    project: (String, String),
}

impl panel::View for Typed {
    type Ask = ();
    const LABEL: &'static str = "compare";

    fn open(&mut self, code: KeyCode, target: Option<(i32, &str)>) -> Option<Stage<()>> {
        (code == KeyCode::Char('c')).then(|| {
            self.input.clear();
            match target {
                Some((id, name)) => {
                    self.project = (id.to_string(), name.to_owned());
                    Stage::Prompt(())
                }
                None => Stage::Shown(error_lines("no project to compare")),
            }
        })
    }

    fn prompt(
        &mut self,
        (): (),
        code: KeyCode,
        cfg: &Config,
        background: bool,
    ) -> Option<Stage<()>> {
        match code {
            KeyCode::Esc => return Some(Stage::Closed),
            KeyCode::Enter => {
                let (cfg, project, input) =
                    (cfg.clone(), self.project.0.clone(), self.input.clone());
                return Some(Stage::run(Self::LABEL, background, move || {
                    body::answer(&cfg, &project, &input)
                }));
            }
            _ => panel::edit(&mut self.input, code),
        }
        None
    }

    fn prompt_lines(&self, (): ()) -> Vec<Line<'static>> {
        vec![
            Line::from(format!("compare {} with: {}▏", self.project.1, self.input)),
            Line::styled(
                "a ref, refs or PROJECT:REF separated by spaces, or the path of a saved export; empty is HEAD",
                theme::muted(),
            ),
        ]
    }

    fn running_lines(&self) -> Vec<Line<'static>> {
        vec![Line::styled(
            format!("comparing {} …", self.project.1),
            theme::muted(),
        )]
    }
}

#[cfg(not(feature = "graph"))]
mod body {
    use super::Answer;
    use crate::config::Config;

    pub(super) fn answer(_: &Config, _: &str, _: &str) -> Answer {
        super::panel::no_graph()
    }
}

#[cfg(feature = "graph")]
mod body {
    use std::path::Path;

    use ratatui::style::{Color, Style};
    use ratatui::text::{Line, Span};

    use super::Answer;
    use crate::config::Config;
    use crate::model::DiffReport;
    use crate::plugin::Runtime;
    use crate::plugins::graph::diff::{self, DiffDef, DiffMove, DiffProject, Saved};
    use crate::tui::theme::{self, ACCENT, ERR, OK, WARN};

    /// The web panel's marks (`CHANGE_MARK` in `compare.ts`); `surface_parity` holds them equal.
    const ADDED: &str = "+";
    const REMOVED: &str = "−";
    const CHANGED: &str = "~";
    const MOVED: &str = "→";
    const CALLERS_SHOWN: usize = 5;

    /// One typed line is a saved export when it names a file, otherwise the old sides as
    /// `rtok graph diff --from` takes them. A file named like a ref wins, as with `git`'s own
    /// ambiguity; the user typed the path in their own terminal, so it is read as given.
    pub(super) fn answer(cfg: &Config, project: &str, input: &str) -> Answer {
        let run = || -> anyhow::Result<DiffReport> {
            let rt = Runtime::open(cfg.clone(), "tui-compare")?;
            let file = Path::new(input.trim());
            if !input.trim().is_empty() && file.is_file() {
                return diff::page_of(&rt, project, &[], None, Some(&Saved::read(file)?));
            }
            let from: Vec<String> = input.split_whitespace().map(String::from).collect();
            diff::page_of(&rt, project, &from, None, None)
        };
        run().map(|r| lines(&r)).map_err(|e| format!("{e:#}"))
    }

    fn mark(m: &str, color: Color) -> Span<'static> {
        Span::styled(format!("{m} "), Style::new().fg(color).bold())
    }

    fn head(title: &str, n: usize) -> Option<Line<'static>> {
        (n > 0).then(|| Line::styled(format!("{title} ({n})"), Style::new().bold()))
    }

    fn row(m: &str, color: Color, text: impl Into<String>) -> Line<'static> {
        Line::from(vec![
            Span::raw("  "),
            mark(m, color),
            Span::raw(text.into()),
        ])
    }

    fn at(d: &DiffDef) -> String {
        format!("{}:{}", d.path, d.line)
    }

    fn defs(
        out: &mut Vec<Line<'static>>,
        title: &str,
        rows: &[DiffDef],
        (m, color): (&str, Color),
    ) {
        out.extend(head(title, rows.len()));
        for d in rows {
            let how = match d.signature_changed {
                Some(true) => "  [signature]",
                Some(false) => "  [body]",
                None => "",
            };
            out.push(row(
                m,
                color,
                format!("{}  {}{how}  {}", d.name, d.kind, at(d)),
            ));
            let callers = d.callers.as_deref().unwrap_or_default();
            for c in callers.iter().take(CALLERS_SHOWN) {
                out.push(Line::styled(format!("      called by {c}"), theme::muted()));
            }
            if callers.len() > CALLERS_SHOWN {
                let more = callers.len() - CALLERS_SHOWN;
                out.push(Line::styled(
                    format!("      +{more} more callers"),
                    theme::muted(),
                ));
            }
        }
    }

    fn moves(
        out: &mut Vec<Line<'static>>,
        title: &str,
        rows: &[DiffMove],
        (m, color): (&str, Color),
    ) {
        out.extend(head(title, rows.len()));
        for r in rows {
            let name = if r.from.name == r.to.name {
                r.to.name.clone()
            } else {
                format!("{} → {}", r.from.name, r.to.name)
            };
            let place = if r.from.path == r.to.path {
                at(&r.to)
            } else {
                format!("{} → {}", r.from.path, r.to.path)
            };
            out.push(row(m, color, format!("{name}  {place}")));
        }
    }

    /// The counts a reader checks against `rtok graph diff`: renamed counts as changed, as in the
    /// web panel.
    fn counts(d: &DiffProject) -> Line<'static> {
        let n = [
            (ADDED, OK, d.added.len(), "added"),
            (REMOVED, ERR, d.removed.len(), "removed"),
            (CHANGED, WARN, d.changed.len() + d.renamed.len(), "changed"),
            (MOVED, ACCENT, d.moved.len(), "moved"),
        ];
        let mut spans = Vec::new();
        for (m, color, n, word) in n {
            spans.push(mark(m, color));
            spans.push(Span::raw(format!("{n} {word}   ")));
        }
        Line::from(spans)
    }

    fn project_lines(out: &mut Vec<Line<'static>>, r: &DiffReport, d: &DiffProject) {
        out.push(Line::styled(
            format!("{} vs {} → {}", d.project, r.from, r.to),
            Style::new().bold(),
        ));
        out.push(counts(d));
        let none = d.added.len()
            + d.removed.len()
            + d.changed.len()
            + d.renamed.len()
            + d.moved.len()
            + d.edges_added.len()
            + d.edges_removed.len()
            + d.not_analysed.len()
            == 0;
        if none {
            out.push(Line::styled("No graph changes.", theme::muted()));
        }
        defs(out, "changed", &d.changed, (CHANGED, WARN));
        defs(out, "removed", &d.removed, (REMOVED, ERR));
        moves(out, "renamed", &d.renamed, (CHANGED, WARN));
        moves(out, "moved", &d.moved, (MOVED, ACCENT));
        defs(out, "added", &d.added, (ADDED, OK));
        for (title, m, color, rows) in [
            ("edges added", ADDED, OK, &d.edges_added),
            ("edges removed", REMOVED, ERR, &d.edges_removed),
        ] {
            out.extend(head(title, rows.len()));
            for e in rows {
                let from = if e.scope.is_empty() {
                    &e.path
                } else {
                    &e.scope
                };
                out.push(row(m, color, format!("{from} → {}", e.name)));
            }
        }
        out.extend(head("changed, not analysed", d.not_analysed.len()));
        for u in &d.not_analysed {
            out.push(row("!", WARN, format!("{}  ({})", u.path, u.reason)));
        }
        if d.more > 0 {
            out.push(Line::styled(
                format!(
                    "{} more rows are not shown; run `rtok graph diff` for all of them.",
                    d.more
                ),
                Style::new().fg(WARN),
            ));
        }
    }

    pub(super) fn lines(r: &DiffReport) -> Vec<Line<'static>> {
        let mut out = Vec::new();
        for d in &r.projects {
            project_lines(&mut out, r, d);
            out.push(Line::default());
        }
        if r.projects.is_empty() {
            out.push(Line::styled(
                "Nothing to compare for this project.",
                theme::muted(),
            ));
        }
        for (title, m, color, rows) in [
            ("links added", ADDED, OK, &r.links_added),
            ("links removed", REMOVED, ERR, &r.links_removed),
        ] {
            out.extend(head(title, rows.len()));
            for l in rows {
                out.push(row(m, color, format!("{} → {} ({})", l.from, l.to, l.kind)));
            }
        }
        out.extend(
            r.notes
                .trim()
                .lines()
                .map(|l| Line::styled(l.to_owned(), theme::muted())),
        );
        out
    }
}

#[cfg(all(test, feature = "graph"))]
mod tests {
    use std::path::{Path, PathBuf};
    use std::process::Command;

    use crossterm::event::KeyModifiers;

    use super::*;
    use crate::model::DiffReport;
    use crate::plugin::Runtime;
    use crate::plugins::graph::diff::{self, Query};
    use crate::plugins::graph::{export, scope};
    use crate::store::Origin;
    use crate::tui::app::{
        App,
        tests::{config, render},
    };

    const OLD: &str = "fn keep() {}\nfn changed() {\n    one();\n}\nfn gone() {\n    bye();\n}\nfn helper() {\n    work();\n}\nfn caller() {\n    changed();\n    gone();\n}\n";
    const NEW: &str = "fn keep() {}\nfn changed() {\n    two();\n}\nfn extra() {\n    hello();\n    world();\n}\nfn caller() {\n    changed();\n}\n";

    fn git(dir: &Path, args: &[&str]) {
        let ok = Command::new("git")
            .current_dir(dir)
            .envs([
                ("GIT_AUTHOR_NAME", "t"),
                ("GIT_AUTHOR_EMAIL", "t@t"),
                ("GIT_COMMITTER_NAME", "t"),
                ("GIT_COMMITTER_EMAIL", "t@t"),
            ])
            .args(args)
            .status()
            .unwrap()
            .success();
        assert!(ok, "{args:?}");
    }

    /// A committed project `p` in a temp dir and its registry row, with the working tree still
    /// equal to HEAD; [`edit`] makes the changes. Never the real home or repo.
    fn fixture() -> (App, Config, PathBuf) {
        let mut cfg = config();
        cfg.tui.tab = "graph".into();
        let base = crate::testutil::tmp_dir("tui-compare");
        let root = base.join("p");
        std::fs::create_dir_all(&root).unwrap();
        std::fs::write(root.join("lib.rs"), OLD).unwrap();
        git(&root, &["init", "-q", "-b", "main"]);
        git(&root, &["add", "-A"]);
        git(&root, &["commit", "-q", "-m", "c"]);
        let rt = Runtime::open(cfg.clone(), "t485-fixture").unwrap();
        rt.store.register_project(&root, Origin::Manual).unwrap();
        (App::new(&cfg), cfg, root)
    }

    /// A changed (and its caller), a removed, an added and a moved symbol.
    fn edit(root: &Path) {
        std::fs::write(root.join("lib.rs"), NEW).unwrap();
        std::fs::write(root.join("util.rs"), "fn helper() {\n    work();\n}\n").unwrap();
    }

    fn type_text(app: &mut App, text: &str) {
        for c in text.chars() {
            assert!(!app.key(KeyCode::Char(c), KeyModifiers::NONE), "{c:?} quit");
        }
    }

    fn compare(app: &mut App, text: &str) {
        type_text(app, "c");
        type_text(app, text);
        app.key(KeyCode::Enter, KeyModifiers::NONE);
    }

    fn report(cfg: &Config, from: &[String]) -> DiffReport {
        let rt = Runtime::open(cfg.clone(), "t485-report").unwrap();
        diff::page_of(&rt, "1", from, None, None).unwrap()
    }

    #[test]
    fn the_report_renders_with_marks_and_the_cli_counts_at_both_widths() {
        let (mut app, cfg, root) = fixture();
        edit(&root);
        compare(&mut app, "");
        let r = report(&cfg, &[]);
        let d = &r.projects[0];
        assert!(!d.changed.is_empty() && d.added.len() == 1 && d.removed.len() == 1);
        assert_eq!(d.moved.len(), 1, "the helper moved to util.rs");

        // The counts line of `rtok graph diff` for the same scope.
        let rt = Runtime::open(cfg.clone(), "t485-cli").unwrap();
        let members = scope::resolve(&rt.store, Some("1"), &root).unwrap();
        let q = Query {
            from: &[],
            to: None,
            json: false,
            export: None,
        };
        let cli = diff::run(&rt, &members, &q).unwrap();
        let counts = format!(
            "{} changed · {} added · {} removed · {} renamed · {} moved",
            d.changed.len(),
            d.added.len(),
            d.removed.len(),
            d.renamed.len(),
            d.moved.len()
        );
        assert!(cli.contains(&counts), "{cli}");

        for width in [100, 60] {
            let shown = render(&app, width);
            for needle in [
                "p vs HEAD → working".to_string(),
                format!("+ {} added", d.added.len()),
                format!("− {} removed", d.removed.len()),
                format!("~ {} changed", d.changed.len() + d.renamed.len()),
                format!("→ {} moved", d.moved.len()),
                format!("changed ({})", d.changed.len()),
                "~ changed".to_string(),
                "[body]".to_string(),
                "called by".to_string(),
                "removed (1)".to_string(),
                "− gone".to_string(),
                "added (1)".to_string(),
                "+ extra".to_string(),
                "moved (1)".to_string(),
                "→ helper".to_string(),
                "lib.rs → util.rs".to_string(),
            ] {
                assert!(
                    shown.contains(&needle),
                    "{width} cols lack {needle}:\n{shown}"
                );
            }
        }
    }

    #[test]
    fn typed_keys_stay_in_the_input_and_esc_returns_to_the_page() {
        let (mut app, _, _) = fixture();
        type_text(&mut app, "c");
        // `?`, `r`, `q` and a digit would be help, refresh, quit and a tab jump anywhere else.
        type_text(&mut app, "r?q3");
        let shown = render(&app, 100);
        assert!(shown.contains("compare p with: r?q3"), "{shown}");
        assert!(!app.help_open());
        assert_eq!(app.page(), "graph");
        app.key(KeyCode::Backspace, KeyModifiers::NONE);
        assert!(render(&app, 100).contains("compare p with: r?q▏"));
        app.key(KeyCode::Esc, KeyModifiers::NONE);
        let shown = render(&app, 100);
        assert!(!shown.contains("compare p with"), "{shown}");
        assert!(shown.contains("c compare"), "{shown}");
        // With the page back, Esc is the shell's again.
        assert!(app.key(KeyCode::Esc, KeyModifiers::NONE));
    }

    #[test]
    fn a_shown_report_keeps_the_shells_keys_and_hides_the_page_keys() {
        let (mut app, _, root) = fixture();
        edit(&root);
        compare(&mut app, "HEAD");
        // `s` would plan a select on the page behind the panel.
        app.key(KeyCode::Char('s'), KeyModifiers::NONE);
        assert!(!render(&app, 100).contains("select p"));
        app.key(KeyCode::Char('?'), KeyModifiers::NONE);
        assert!(render(&app, 128).contains("keys — compare"));
        app.key(KeyCode::Char('?'), KeyModifiers::NONE);
        app.key(KeyCode::Down, KeyModifiers::NONE);
        app.key(KeyCode::PageDown, KeyModifiers::NONE);
        app.key(KeyCode::Home, KeyModifiers::NONE);
        assert!(app.key(KeyCode::Char('q'), KeyModifiers::NONE));
        app.key(KeyCode::Right, KeyModifiers::NONE);
        assert_ne!(app.page(), "graph");
    }

    #[test]
    fn a_ref_that_does_not_exist_is_a_line_not_a_crash() {
        let (mut app, _, _) = fixture();
        compare(&mut app, "no-such-ref");
        let shown = render(&app, 100);
        assert!(shown.contains('✕'), "{shown}");
        // Compare again from the error.
        type_text(&mut app, "c");
        assert!(render(&app, 100).contains("compare p with:"));
    }

    #[test]
    fn a_saved_export_typed_as_a_path_is_the_old_side() {
        let (mut app, cfg, root) = fixture();
        let rt = Runtime::open(cfg.clone(), "t485-export").unwrap();
        let members = scope::resolve(&rt.store, Some("1"), &root).unwrap();
        let text = export::run(
            &rt,
            &members,
            &export::Query {
                level: export::Level::Symbols,
                focus: None,
                depth: 2,
                redact: false,
                pretty: false,
                from: None,
            },
        )
        .unwrap();
        let file = root.parent().unwrap().join("saved.json");
        std::fs::write(&file, text).unwrap();
        edit(&root);
        compare(&mut app, file.to_str().unwrap());
        let shown = render(&app, 100);
        assert!(
            shown.contains("added (2)") && shown.contains("+ extra"),
            "{shown}"
        );
        assert!(
            shown.contains("removed (2)") && shown.contains("− gone"),
            "{shown}"
        );
        assert!(shown.contains("against an export"), "{shown}");

        // A file that is not an export is refused with the reason.
        let junk = root.parent().unwrap().join("junk.json");
        std::fs::write(&junk, "{}").unwrap();
        type_text(&mut app, "c");
        type_text(&mut app, junk.to_str().unwrap());
        app.key(KeyCode::Enter, KeyModifiers::NONE);
        assert!(render(&app, 100).contains('✕'));
    }

    #[test]
    fn no_project_row_means_no_compare() {
        let mut cfg = config();
        cfg.tui.tab = "graph".into();
        let mut app = App::new(&cfg);
        type_text(&mut app, "c");
        assert!(render(&app, 100).contains("no project to compare"));
    }

    #[test]
    fn the_background_run_answers_through_poll_and_esc_drops_it() {
        let (_, cfg, root) = fixture();
        edit(&root);
        let mut c = Compare::default();
        let go = |c: &mut Compare, code| c.key(code, Some((1, "p")), &cfg, true);
        go(&mut c, KeyCode::Char('c'));
        assert!(go(&mut c, KeyCode::Enter));
        let deadline = std::time::Instant::now() + std::time::Duration::from_secs(60);
        while matches!(c.stage, Stage::Running(_)) {
            assert!(std::time::Instant::now() < deadline, "no answer");
            std::thread::sleep(std::time::Duration::from_millis(20));
            c.poll();
        }
        let Stage::Shown(lines) = &c.stage else {
            panic!("shown")
        };
        assert!(lines.iter().any(|l| l.to_string().contains("+ extra")));
        go(&mut c, KeyCode::Char('c'));
        go(&mut c, KeyCode::Enter);
        go(&mut c, KeyCode::Esc);
        assert!(!c.is_open());
    }
}
