// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! Emoji and colour for rtok's own human-facing lines (status, success, warning, error,
//! summaries, `--help`). One table, so every command marks a warning the same way.
//!
//! A line that names an operation (`index`, `start`, `remove`, …) takes that operation's icon
//! from [`OPERATION_ICONS`], the way ketch draws them; any other line falls back to the icon
//! of its [`Kind`]. Every icon is padded to [`ICON_WIDTH`] columns so the text after it starts
//! in one column whichever icon a line carries.
//!
//! What agents read stays plain: the filtered command output, hook and MCP JSON, `--json`,
//! and anything written to a pipe or a file. Two rules keep it that way:
//! - an emoji needs `[ui] emoji` *and* a terminal on that stream;
//! - colour needs `[ui] color` *and* owo-colors' answer for that stream — a terminal,
//!   `NO_COLOR` unset, `TERM` not `dumb`, or `CLICOLOR_FORCE` / `FORCE_COLOR` set — the same
//!   check `render` uses for diffs and log levels.
//!
//! `[ui] color = false` turns colour off process-wide (owo-colors' override), so the diffs,
//! state words and log levels in [`crate::render`] follow the same key.

use std::io::IsTerminal;
use std::sync::atomic::{AtomicBool, Ordering};

use clap::builder::styling::{AnsiColor, Effects, Styles};
use owo_colors::{OwoColorize, Stream};
use unicode_width::UnicodeWidthStr;

use crate::config::Ui;

/// `[ui] emoji`, as last loaded. On until a config says otherwise.
static EMOJI: AtomicBool = AtomicBool::new(true);

/// `--help` and clap's own errors. clap decides colour itself (terminal, `NO_COLOR`,
/// `CLICOLOR_FORCE`); the config is not loaded yet when it parses argv.
pub const CLAP: Styles = Styles::styled()
    .header(AnsiColor::Green.on_default().effects(Effects::BOLD))
    .usage(AnsiColor::Green.on_default().effects(Effects::BOLD))
    .literal(AnsiColor::Cyan.on_default().effects(Effects::BOLD))
    .placeholder(AnsiColor::Cyan.on_default())
    .error(AnsiColor::Red.on_default().effects(Effects::BOLD))
    .valid(AnsiColor::Green.on_default())
    .invalid(AnsiColor::Yellow.on_default());

/// What a line reports. Picks the emoji and the colour together.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Kind {
    Success,
    Info,
    Warn,
    Error,
}

/// Icons for what a verb is doing, looked up before the [`Kind`]'s own. Same model as ketch's
/// table; the rows from `index` down are rtok's own verbs.
///
/// Matched as substrings of the verb, in order, so `installing` and `installed` share one row
/// and `uninstall` must come before `install`, which it contains. Each icon is one code point
/// that is wide by default: a narrow symbol made wide by U+FE0F (`⚠️`) is two columns to
/// `unicode-width` but one to a terminal that ignores the selector.
pub const OPERATION_ICONS: &[(&str, &str)] = &[
    ("uninstall", "🧹"),
    ("remov", "🧹"),
    ("prun", "🧹"),
    ("install", "📦"),
    ("upgrad", "⏫"),
    ("updat", "⏫"),
    ("download", "⏬"),
    ("fetch", "⏬"),
    ("link", "🔗"),
    ("roll", "⏪"),
    ("search", "🔍"),
    ("doctor", "🩺"),
    ("index", "📚"),
    ("worktree", "🌳"),
    ("compress", "📉"),
    ("expand", "📂"),
    ("bench", "🏁"),
    ("start", "🚀"),
    ("stop", "🛑"),
];

/// Columns every icon is padded to. Each icon is still measured rather than assumed, so a
/// narrower one added later pads out instead of pulling its line left.
pub const ICON_WIDTH: usize = 2;

/// The icon a line carries: its operation's when `verb` names one, else its `kind`'s.
pub fn icon(verb: &str, kind: Kind) -> &'static str {
    let verb = verb.to_ascii_lowercase();
    OPERATION_ICONS
        .iter()
        .find(|(key, _)| verb.contains(key))
        .map_or_else(|| kind.emoji(), |(_, icon)| icon)
}

/// `icon`, blank-padded to [`ICON_WIDTH`] columns, and one space.
fn gutter(icon: &str) -> String {
    let pad = ICON_WIDTH.saturating_sub(UnicodeWidthStr::width(icon));
    format!("{icon}{} ", " ".repeat(pad))
}

impl Kind {
    pub fn emoji(self) -> &'static str {
        match self {
            Kind::Success => "✅",
            Kind::Info => "💡",
            Kind::Warn => "❗",
            Kind::Error => "❌",
        }
    }
}

/// Apply `[ui]` for the rest of the process. Called by every config load.
pub fn configure(ui: &Ui) {
    EMOJI.store(ui.emoji, Ordering::Relaxed);
    if ui.color {
        owo_colors::unset_override();
    } else {
        owo_colors::set_override(false);
    }
}

/// The toggle rule, without the process state: an emoji needs the key and a terminal.
pub fn emoji_on(configured: bool, terminal: bool) -> bool {
    configured && terminal
}

fn terminal(stream: Stream) -> bool {
    match stream {
        Stream::Stdout => std::io::stdout().is_terminal(),
        Stream::Stderr => std::io::stderr().is_terminal(),
    }
}

/// `text` painted in `kind`'s colour where `stream` takes colour, else unchanged.
fn paint(kind: Kind, text: &str, stream: Stream) -> String {
    match kind {
        Kind::Success => text.if_supports_color(stream, |t| t.green()).to_string(),
        Kind::Info => text.if_supports_color(stream, |t| t.cyan()).to_string(),
        Kind::Warn => text.if_supports_color(stream, |t| t.yellow()).to_string(),
        Kind::Error => text.if_supports_color(stream, |t| t.red()).to_string(),
    }
}

/// `text` with the icon of `verb` (else of `kind`) in front when `emoji` is set.
pub fn prefix(kind: Kind, verb: &str, text: &str, emoji: bool) -> String {
    if emoji {
        format!("{}{text}", gutter(icon(verb, kind)))
    } else {
        text.to_string()
    }
}

/// One line for `stream` that names the operation `verb`: its icon and colour where both are
/// on, the bare text otherwise.
pub fn line_op(verb: &str, kind: Kind, stream: Stream, text: &str) -> String {
    let emoji = emoji_on(EMOJI.load(Ordering::Relaxed), terminal(stream));
    prefix(kind, verb, &paint(kind, text, stream), emoji)
}

/// One line for `stream` with no operation: the `kind`'s icon.
pub fn line(kind: Kind, stream: Stream, text: &str) -> String {
    line_op("", kind, stream, text)
}

/// A success line for stdout that names the operation `verb` (`index`, `start`, …).
pub fn success_op(verb: &str, text: &str) -> String {
    line_op(verb, Kind::Success, Stream::Stdout, text)
}

/// A success line for stdout (`ok …`, `… started`).
pub fn success(text: &str) -> String {
    line(Kind::Success, Stream::Stdout, text)
}

/// A neutral status line for stdout (`… not running`, `nothing to update`).
pub fn info(text: &str) -> String {
    line(Kind::Info, Stream::Stdout, text)
}

/// A warning line for stderr (`warning: …`).
pub fn warn(text: &str) -> String {
    line(Kind::Warn, Stream::Stderr, text)
}

/// An error line for stderr (`Error: …`).
pub fn error(text: &str) -> String {
    line(Kind::Error, Stream::Stderr, text)
}

#[cfg(test)]
mod tests {
    use super::*;
    use rstest::rstest;

    #[rstest]
    #[case(true, true, true)]
    #[case(true, false, false)]
    #[case(false, true, false)]
    #[case(false, false, false)]
    fn an_emoji_needs_the_key_and_a_terminal(
        #[case] key: bool,
        #[case] tty: bool,
        #[case] on: bool,
    ) {
        assert_eq!(emoji_on(key, tty), on);
    }

    #[test]
    fn the_prefix_is_one_emoji_and_a_space() {
        assert_eq!(prefix(Kind::Success, "", "ok x", true), "✅ ok x");
        assert_eq!(prefix(Kind::Warn, "", "warning: y", true), "❗ warning: y");
        assert_eq!(prefix(Kind::Error, "", "Error: z", false), "Error: z");
    }

    #[rstest]
    #[case("uninstall", "🧹")]
    #[case("removed", "🧹")]
    #[case("pruning", "🧹")]
    #[case("installing", "📦")]
    #[case("agents install", "📦")]
    #[case("upgrade", "⏫")]
    #[case("update", "⏫")]
    #[case("download", "⏬")]
    #[case("fetched", "⏬")]
    #[case("linked", "🔗")]
    #[case("rolled back", "⏪")]
    #[case("search", "🔍")]
    #[case("doctor", "🩺")]
    #[case("indexed", "📚")]
    #[case("worktree add", "🌳")]
    #[case("compress", "📉")]
    #[case("expanded", "📂")]
    #[case("bench", "🏁")]
    #[case("proxy start", "🚀")]
    #[case("stopped", "🛑")]
    #[case("Installed", "📦")]
    fn a_verb_takes_its_operations_icon(#[case] verb: &str, #[case] want: &str) {
        assert_eq!(icon(verb, Kind::Success), want);
    }

    /// `uninstall` contains `install`, so the order of the table is part of its meaning.
    #[test]
    fn a_worktree_removal_is_a_removal_not_a_worktree() {
        assert_eq!(icon("worktree remove", Kind::Success), "🧹");
        assert_eq!(icon("uninstall", Kind::Success), "🧹");
    }

    #[rstest]
    #[case(Kind::Success, "✅")]
    #[case(Kind::Info, "💡")]
    #[case(Kind::Warn, "❗")]
    #[case(Kind::Error, "❌")]
    fn a_verb_no_row_names_falls_back_to_the_kind(#[case] kind: Kind, #[case] want: &str) {
        assert_eq!(icon("", kind), want);
        assert_eq!(icon("frobnicate", kind), want);
    }

    #[test]
    fn every_icon_fills_the_same_gutter() {
        let kinds = [Kind::Success, Kind::Info, Kind::Warn, Kind::Error].map(Kind::emoji);
        for icon in OPERATION_ICONS.iter().map(|(_, icon)| *icon).chain(kinds) {
            assert_eq!(UnicodeWidthStr::width(icon), ICON_WIDTH, "{icon}");
            assert_eq!(icon.chars().count(), 1, "{icon} needs a selector");
        }
    }

    /// The text after the icon starts in one column for every icon, even a narrower one.
    #[test]
    fn the_text_after_an_icon_starts_in_one_column() {
        let lines = [
            prefix(Kind::Success, "indexed", "x", true),
            prefix(Kind::Info, "", "x", true),
            prefix(Kind::Success, "uninstall", "x", true),
        ];
        for l in lines {
            assert_eq!(UnicodeWidthStr::width(l.as_str()), ICON_WIDTH + 2, "{l}");
        }
        assert_eq!(gutter("→"), "→  ");
    }

    #[test]
    fn every_kind_has_its_own_emoji() {
        let all = [Kind::Success, Kind::Info, Kind::Warn, Kind::Error].map(Kind::emoji);
        let mut uniq = all.to_vec();
        uniq.sort_unstable();
        uniq.dedup();
        assert_eq!(uniq.len(), all.len());
    }

    /// The tests run without a terminal: every line is its bare text, byte for byte, which
    /// is what a pipe, a hook or a trycmd fixture sees.
    #[test]
    fn off_a_terminal_a_line_is_its_bare_text() {
        assert_eq!(success_op("index", "indexed a"), "indexed a");
        assert_eq!(success("ok a"), "ok a");
        assert_eq!(info("b not running"), "b not running");
        assert_eq!(warn("warning: c"), "warning: c");
        assert_eq!(error("Error: d"), "Error: d");
    }

    /// Colour is owo-colors' call per stream; `[ui] color = false` overrides it off even
    /// where it would paint, and `true` hands the decision back.
    #[test]
    fn the_color_key_overrides_a_forced_terminal() {
        owo_colors::with_override(true, || {
            assert_eq!(
                paint(Kind::Error, "x", Stream::Stderr),
                "\u{1b}[31mx\u{1b}[39m"
            );
        });
        configure(&Ui {
            emoji: false,
            color: false,
        });
        assert_eq!(paint(Kind::Error, "x", Stream::Stderr), "x");
        assert!(!EMOJI.load(Ordering::Relaxed));
        configure(&Ui::default());
        assert!(EMOJI.load(Ordering::Relaxed));
    }
}
