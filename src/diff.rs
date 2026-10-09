// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! Unified diffs for commands that change files (plan T12.6, T20.2).
//!
//! Colour follows owo-colors' `if_supports_color`: a tty, `NO_COLOR`,
//! `CLICOLOR` / `CLICOLOR_FORCE`, and `TERM=dumb`. A pipe stays plain text.
//! Config validation renders the same diff without depending on `render`.

use std::path::Path;

use owo_colors::{OwoColorize, Stream};

/// Uncoloured unified diff of one file, three lines of context. Empty when nothing differs.
pub fn unified_diff(path: &Path, before: &str, after: &str) -> String {
    if before == after {
        return String::new();
    }
    similar::TextDiff::from_lines(before, after)
        .unified_diff()
        .context_radius(3)
        .header(
            &format!("a/{}", path.display()),
            &format!("b/{}", path.display()),
        )
        .to_string()
}

/// A `git diff` of one file, three lines of context, coloured. Empty when nothing differs.
pub fn file_diff(path: &Path, before: &str, after: &str) -> String {
    paint(&unified_diff(path, before, after))
}

/// Colour diff-shaped text: green additions, red removals, cyan hunk headers, bold file headers.
/// Lines that carry no marker — the installers' own `7 additions`, `no changes` — pass through.
pub fn paint(text: &str) -> String {
    text.lines()
        .map(|line| {
            if line.starts_with("+++") || line.starts_with("---") {
                line.if_supports_color(Stream::Stdout, |t| t.bold())
                    .to_string()
            } else if line.starts_with('@') {
                line.if_supports_color(Stream::Stdout, |t| t.cyan())
                    .to_string()
            } else if line.starts_with('+') {
                line.if_supports_color(Stream::Stdout, |t| t.green())
                    .to_string()
            } else if line.starts_with('-') {
                line.if_supports_color(Stream::Stdout, |t| t.red())
                    .to_string()
            } else {
                line.to_string()
            }
        })
        .collect::<Vec<_>>()
        .join("\n")
}
