// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! Spinners for waits with no known length.
//!
//! indicatif draws to stderr and draws nothing when stderr is not a terminal,
//! so a piped or redirected run stays byte-clean. The store's migration wait
//! uses this directly; `render` re-exports the same functions for the CLI.

/// A spinner with no file counter. Silent when stderr is not a terminal.
pub fn loader(what: &str) -> indicatif::ProgressBar {
    let pb = indicatif::ProgressBar::new_spinner();
    if let Ok(style) = indicatif::ProgressStyle::with_template("{spinner:.cyan} {msg}") {
        pb.set_style(style);
    }
    pb.set_message(what.to_string());
    pb.enable_steady_tick(std::time::Duration::from_millis(120));
    pb
}

/// Run `f` behind a [`loader`] and clear it before returning, so the finished line the caller
/// prints next takes its place. Silent off a terminal, as [`loader`] is.
pub fn with_loader<T>(msg: &str, f: impl FnOnce() -> T) -> T {
    let pb = loader(msg);
    let out = f();
    pb.finish_and_clear();
    out
}
