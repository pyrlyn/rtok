// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! The terminal hooks. Library code hands text here; `cli::run` prints it.
//!
//! This is foundation, not `log`: `log` already uses `store`, and the retention
//! thread in `store` has to report warnings without closing that cycle.

use std::sync::Mutex;

static STDOUT: Mutex<Option<fn(&str)>> = Mutex::new(None);
static STDERR: Mutex<Option<fn(&str)>> = Mutex::new(None);

fn hook_slot(slot: &Mutex<Option<fn(&str)>>) -> std::sync::MutexGuard<'_, Option<fn(&str)>> {
    slot.lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner)
}

/// The process surface (`cli::run`) registers these. Library code hands it text; it does not
/// touch the terminal itself, so a hook or MCP stdio stream stays quiet unless that surface
/// asked to print.
pub fn on_stdout(hook: fn(&str)) {
    *hook_slot(&STDOUT) = Some(hook);
}

pub fn on_stderr(hook: fn(&str)) {
    *hook_slot(&STDERR) = Some(hook);
}

fn emit(slot: &Mutex<Option<fn(&str)>>, text: &str) {
    if let Some(hook) = *hook_slot(slot) {
        hook(text);
    }
}

/// Bytes for stdout, exactly as the caller built them (no added newline).
pub fn stdout(text: &str) {
    emit(&STDOUT, text);
}

/// One stdout line, the `println!` shape.
pub fn stdout_ln(text: &str) {
    stdout(&format!("{text}\n"));
}

/// Bytes for stderr, exactly as the caller built them.
pub fn stderr(text: &str) {
    emit(&STDERR, text);
}

/// One stderr line, the `eprintln!` shape.
pub fn stderr_ln(text: &str) {
    stderr(&format!("{text}\n"));
}
