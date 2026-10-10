// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! T481 (D27): the graph health score on the Graph page. The numbers, levels, reasons and fixes
//! are the server's [`Score`](crate::plugins::graph::health::score::Score), already in the
//! snapshot's registry rows; this only paints them. The colour follows the row's `level`, as the
//! web page does, never a re-derived band; only the scope's bare number goes through
//! [`level_of`](crate::plugins::graph::health::score::level_of), the one place the cut-offs live.

#[cfg(feature = "graph")]
mod on {
    use ratatui::style::{Color, Style};
    use ratatui::text::{Line, Span};
    use ratatui::widgets::Cell;

    use crate::plugins::graph::health::score::{Level, level_of};
    use crate::tui::projects::Entry;
    use crate::tui::theme::{ERR, MUTED, OK, WARN};

    /// Reasons shown under the table; the page below is for the rest.
    const MAX_REASONS: usize = 3;

    fn color(level: Level) -> Color {
        match level {
            Level::Good => OK,
            Level::Warn => WARN,
            Level::Bad | Level::Missing => ERR,
            Level::Indexing => MUTED,
        }
    }

    fn word(level: Level) -> &'static str {
        match level {
            Level::Good => "good",
            Level::Warn => "warn",
            Level::Bad => "bad",
            Level::Indexing => "indexing",
            Level::Missing => "missing",
        }
    }

    /// The table cell: the number, or the word where there is no number to read.
    pub fn cell(p: &Entry) -> Cell<'static> {
        let text = match (p.health.level, p.health.score) {
            (Level::Indexing | Level::Missing, _) | (_, None) => word(p.health.level).to_string(),
            (_, Some(n)) => n.to_string(),
        };
        Cell::from(Span::styled(text, Style::new().fg(color(p.health.level))))
    }

    pub fn scope_cell(p: &Entry) -> Cell<'static> {
        match p.scope_health {
            Some(n) => Cell::from(Span::styled(
                n.to_string(),
                Style::new().fg(color(level_of(n))),
            )),
            None => Cell::from("-"),
        }
    }

    /// The cursor project's breakdown: the level and three components, then each reason with its
    /// fix.
    pub fn detail(p: &Entry) -> Vec<Line<'static>> {
        let h = &p.health;
        let c = &h.components;
        let head = Line::from(vec![
            Span::styled(
                format!("{} {}", p.name, word(h.level)),
                Style::new().fg(color(h.level)),
            ),
            Span::raw(format!(
                "  freshness {:.2}  backend {:.2}  links {:.2}",
                c.freshness, c.backend, c.links
            )),
        ]);
        let reasons = h.reasons.iter().take(MAX_REASONS).map(|r| {
            Line::from(format!(
                "  {}: {} -> {}",
                format!("{:?}", r.component).to_lowercase(),
                r.text,
                r.fix
            ))
        });
        std::iter::once(head).chain(reasons).collect()
    }
}

#[cfg(not(feature = "graph"))]
mod on {
    use ratatui::text::Line;
    use ratatui::widgets::Cell;

    use crate::tui::projects::Entry;

    pub fn cell(_: &Entry) -> Cell<'static> {
        Cell::default()
    }

    pub fn scope_cell(_: &Entry) -> Cell<'static> {
        Cell::default()
    }

    pub fn detail(_: &Entry) -> Vec<Line<'static>> {
        Vec::new()
    }
}

pub(super) use on::{cell, detail, scope_cell};

#[cfg(all(test, feature = "graph"))]
mod tests {
    use ratatui::backend::TestBackend;
    use ratatui::style::Color;

    use crate::model::Snapshot;
    use crate::plugins::graph::health::score::{Component, Components, Level, Reason, Score};
    use crate::plugins::graph::projects::ProjectRow;
    use crate::tui::app::{App, tests::config};
    use crate::tui::theme::{ERR, MUTED, OK, WARN};
    use crate::tui::view::draw;

    fn score(n: Option<u8>, level: Level, reasons: Vec<Reason>) -> Score {
        Score {
            score: n,
            level,
            components: Components {
                freshness: if n == Some(60) { 0.0 } else { 1.0 },
                backend: 1.0,
                links: 1.0,
            },
            reasons,
        }
    }

    fn reason(component: Component, text: &str, fix: &str) -> Reason {
        Reason {
            component,
            text: text.into(),
            fix: fix.into(),
        }
    }

    fn page(rows: Vec<ProjectRow>, cursor: usize) -> (String, ratatui::buffer::Buffer) {
        let mut cfg = config();
        cfg.tui.tab = "graph".into();
        let mut app = App::new(&cfg);
        app.refresh(Snapshot {
            projects: Some(rows),
            ..Snapshot::default()
        });
        for _ in 0..cursor {
            app.key(
                crossterm::event::KeyCode::Down,
                crossterm::event::KeyModifiers::NONE,
            );
        }
        let mut terminal = ratatui::Terminal::new(TestBackend::new(128, 24)).unwrap();
        terminal.draw(|f| draw(f, &app)).unwrap();
        let buf = terminal.backend().buffer().clone();
        let text = (0..buf.area.height)
            .map(|y| {
                (0..buf.area.width)
                    .map(|x| buf[(x, y)].symbol().to_string())
                    .collect::<String>()
            })
            .collect::<Vec<_>>()
            .join("\n");
        (text, buf)
    }

    /// The colour of the first cell of `word` in the table row of project `name`. Columns are
    /// counted in chars: the border is multi-byte.
    fn color_of(text: &str, buf: &ratatui::buffer::Buffer, name: &str, word: &str) -> Color {
        let (y, line) = text
            .lines()
            .enumerate()
            .find(|(_, l)| l.contains(name) && l.contains("indexed"))
            .unwrap_or_else(|| panic!("no row {name}:\n{text}"));
        let chars: Vec<char> = line.chars().collect();
        let from = line[line.find("indexed").unwrap()..].to_string();
        let x = chars.len() - from.chars().count() + from.find(word).unwrap();
        buf[(x as u16, y as u16)].fg
    }

    #[test]
    fn score_levels_and_the_breakdown_follow_the_servers_fields() {
        let rows = vec![
            ProjectRow::fixture(1, "full", score(Some(100), Level::Good, vec![]), Some(100)),
            ProjectRow::fixture(
                2,
                "stale",
                score(
                    Some(60),
                    Level::Warn,
                    vec![
                        reason(
                            Component::Freshness,
                            "3 files pending (30%)",
                            "run `rtok graph index /stale`",
                        ),
                        reason(
                            Component::Backend,
                            "server missing, using tree-sitter",
                            "install the server",
                        ),
                    ],
                ),
                Some(60),
            ),
            ProjectRow::fixture(3, "first", score(None, Level::Indexing, vec![]), None),
            ProjectRow::fixture(4, "gone", score(Some(0), Level::Missing, vec![]), Some(0)),
        ];
        let (text, buf) = page(rows, 1);
        assert_eq!(color_of(&text, &buf, "full", "100"), OK);
        // The cursor row wears the table's highlight, so its level colour is the breakdown's.
        let (y, line) = text
            .lines()
            .enumerate()
            .find(|(_, l)| l.contains("stale warn"))
            .unwrap();
        assert_eq!(buf[(2, y as u16)].fg, WARN, "{line}");
        assert_eq!(color_of(&text, &buf, "first", "indexing"), MUTED);
        assert_eq!(color_of(&text, &buf, "gone", "missing"), ERR);

        // The cursor is on `stale`: its breakdown, both reasons with their fixes, and the scope.
        assert!(
            text.contains("stale warn  freshness 0.00  backend 1.00  links 1.00"),
            "{text}"
        );
        assert!(text.contains("freshness: 3 files pending (30%) -> run `rtok graph index /stale`"));
        assert!(text.contains("backend: server missing, using tree-sitter -> install the server"));
        assert!(
            !text.contains("full good"),
            "only the cursor row has a breakdown"
        );
    }

    #[test]
    fn the_level_decides_the_colour_not_the_number() {
        // A server that says Bad for 70 is painted red: the TUI never re-derives the bands.
        let rows = vec![
            ProjectRow::fixture(1, "odd", score(Some(70), Level::Bad, vec![]), Some(49)),
            ProjectRow::fixture(
                2,
                "cursor",
                score(Some(100), Level::Good, vec![]),
                Some(100),
            ),
        ];
        let (text, buf) = page(rows, 1);
        assert_eq!(color_of(&text, &buf, "odd", "70"), ERR);
        assert_eq!(
            color_of(&text, &buf, "odd", "49"),
            ERR,
            "the scope number goes through level_of"
        );
    }
}
