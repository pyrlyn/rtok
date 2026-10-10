// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! The shell the Graph page's panels share (T329.48): compare ([`super::compare`], T485) and
//! export ([`super::exporter`], T329.41). A panel replaces the page body while it is open; this
//! module is the stage it moves through, the background run, the scroll keys, the keys it leaves
//! to the tab shell and the two-row layout with the hints line. What a panel asks for and what it
//! answers is its [`View`].

use std::sync::mpsc;

use crossterm::event::KeyCode;
use ratatui::Frame;
use ratatui::layout::{Constraint, Layout, Rect};
use ratatui::text::Line;
use ratatui::widgets::{Paragraph, Wrap};

use super::theme;
use super::view::status_line;
use crate::config::Config;

/// Rows one PageUp or PageDown moves.
const PAGE: u16 = 10;

pub(super) type Answer = Result<Vec<Line<'static>>, String>;

/// `A` is what the panel is asking the user for while a field has the keys.
pub(super) enum Stage<A> {
    Closed,
    Prompt(A),
    /// Computing off the key loop; Esc drops the receiver and with it the answer.
    Running(mpsc::Receiver<Answer>),
    Shown(Vec<Line<'static>>),
}

pub(super) fn error_lines(e: &str) -> Vec<Line<'static>> {
    vec![theme::banner('✕', e, theme::ERR)]
}

/// The answer a build without the graph feature gives.
#[cfg(not(feature = "graph"))]
pub(super) fn no_graph() -> Answer {
    Err("the graph feature is not built in".into())
}

impl<A> Stage<A> {
    /// Runs `work` and shows its answer; off the key loop when `background`. `label` names the
    /// panel in the thread and in the errors.
    pub(super) fn run(
        label: &str,
        background: bool,
        work: impl FnOnce() -> Answer + Send + 'static,
    ) -> Self {
        if !background {
            return Self::Shown(work().unwrap_or_else(|e| error_lines(&e)));
        }
        let (tx, rx) = mpsc::channel();
        let spawned = std::thread::Builder::new()
            .name(format!("rtok-tui-{label}"))
            .spawn(move || tx.send(work()));
        match spawned {
            Ok(_) => Self::Running(rx),
            Err(e) => Self::Shown(error_lines(&format!("{label} did not start: {e}"))),
        }
    }
}

/// What differs between panels: the keys that open it, its fields and what it says.
pub(super) trait View: Default {
    type Ask: Copy;
    /// Names the panel in the thread, the errors and the hints line.
    const LABEL: &'static str;

    /// The next stage when `code` opens the panel (from closed, or over a result); `None` when
    /// it is not an opening key. `target` is the registry row under the cursor (id, name).
    fn open(&mut self, code: KeyCode, target: Option<(i32, &str)>) -> Option<Stage<Self::Ask>>;

    /// A key for the field `ask`; the next stage, or `None` to stay.
    fn prompt(
        &mut self,
        ask: Self::Ask,
        code: KeyCode,
        cfg: &Config,
        background: bool,
    ) -> Option<Stage<Self::Ask>>;

    fn prompt_lines(&self, ask: Self::Ask) -> Vec<Line<'static>>;

    fn running_lines(&self) -> Vec<Line<'static>>;
}

pub(super) struct Panel<V: View> {
    pub(super) stage: Stage<V::Ask>,
    scroll: u16,
    view: V,
}

impl<V: View> Default for Panel<V> {
    fn default() -> Self {
        Self {
            stage: Stage::Closed,
            scroll: 0,
            view: V::default(),
        }
    }
}

impl<V: View> Panel<V> {
    pub(super) fn is_open(&self) -> bool {
        !matches!(self.stage, Stage::Closed)
    }

    /// Whether keys go to a field, ahead of the shell's own (`?`, `r`, `q`).
    pub(super) fn typing(&self) -> bool {
        matches!(self.stage, Stage::Prompt(_))
    }

    /// Returns whether the panel took the key; the shell keeps its own tab, quit and digit keys
    /// while a result is up.
    pub(super) fn key(
        &mut self,
        code: KeyCode,
        target: Option<(i32, &str)>,
        cfg: &Config,
        background: bool,
    ) -> bool {
        if self.is_open()
            && !self.typing()
            && matches!(
                code,
                KeyCode::Left | KeyCode::Right | KeyCode::Char('q' | '1'..='9')
            )
        {
            return false;
        }
        let next = match &self.stage {
            Stage::Closed => match self.open(code, target) {
                Some(next) => Some(next),
                None => return false,
            },
            Stage::Prompt(ask) => self.view.prompt(*ask, code, cfg, background),
            Stage::Running(_) => (code == KeyCode::Esc).then_some(Stage::Closed),
            Stage::Shown(lines) => {
                let len = lines.len();
                match code {
                    KeyCode::Esc => Some(Stage::Closed),
                    _ => {
                        let opened = self.open(code, target);
                        if opened.is_none() {
                            self.scroll_key(len, code);
                        }
                        opened
                    }
                }
            }
        };
        if let Some(next) = next {
            self.stage = next;
        }
        true
    }

    fn open(&mut self, code: KeyCode, target: Option<(i32, &str)>) -> Option<Stage<V::Ask>> {
        let next = self.view.open(code, target)?;
        self.scroll = 0;
        Some(next)
    }

    fn scroll_key(&mut self, len: usize, code: KeyCode) {
        let last = u16::try_from(len.saturating_sub(1)).unwrap_or(u16::MAX);
        let at = self.scroll;
        self.scroll = match code {
            KeyCode::Up => at.saturating_sub(1),
            KeyCode::Down => at.saturating_add(1).min(last),
            KeyCode::PageUp => at.saturating_sub(PAGE),
            KeyCode::PageDown => at.saturating_add(PAGE).min(last),
            KeyCode::Home => 0,
            _ => at,
        };
    }

    /// Takes a finished run, if any. Called by the loop between keys.
    pub(super) fn poll(&mut self) {
        let Stage::Running(rx) = &self.stage else {
            return;
        };
        self.stage = match rx.try_recv() {
            Ok(answer) => Stage::Shown(answer.unwrap_or_else(|e| error_lines(&e))),
            Err(mpsc::TryRecvError::Empty) => return,
            Err(mpsc::TryRecvError::Disconnected) => Stage::Shown(error_lines(&format!(
                "{} stopped without an answer",
                V::LABEL
            ))),
        };
    }

    /// Draws the panel over `area`; `false` when it is closed and the page draws itself.
    pub(super) fn render(&self, frame: &mut Frame, area: Rect) -> bool {
        let lines = match &self.stage {
            Stage::Closed => return false,
            Stage::Prompt(ask) => self.view.prompt_lines(*ask),
            Stage::Running(_) => self.view.running_lines(),
            Stage::Shown(lines) => lines.clone(),
        };
        let [text, hints] =
            Layout::vertical([Constraint::Min(0), Constraint::Length(2)]).areas(area);
        frame.render_widget(
            Paragraph::new(lines)
                .wrap(Wrap { trim: false })
                .scroll((self.scroll, 0)),
            text,
        );
        frame.render_widget(
            Paragraph::new(status_line(V::LABEL, "")).wrap(Wrap { trim: true }),
            hints,
        );
        true
    }
}

/// Edits a typed line.
pub(super) fn edit(input: &mut String, code: KeyCode) {
    match code {
        KeyCode::Backspace => {
            input.pop();
        }
        KeyCode::Char(c) => input.push(c),
        _ => {}
    }
}
