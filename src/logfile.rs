// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! Rotating text log plus the `RUST_LOG` mirror, without the `logs` table.
//!
//! [`crate::log::record`] is the path that also inserts a database row.
//! Config load and store retention only need the file, and they must not
//! depend on [`crate::log`] (that module owns the store-backed funnel).

use std::path::Path;

/// Target of every mirrored line, so `RUST_LOG=rtok::log=info` selects the D26 stream alone.
const STDERR_TARGET: &str = "rtok::log";

/// Append one line, rotating first when it would take the file past `max_bytes`.
///
/// `gate` is `[log] level`. The stderr mirror runs before that gate, matching
/// [`crate::log::append`]. Never fails upward (D1).
#[allow(clippy::too_many_arguments)]
pub fn append(
    path: &Path,
    max_bytes: u64,
    files: u32,
    gate: &str,
    level: &str,
    source: &str,
    name: &str,
    message: &str,
) {
    mirror(level, source, name, message);
    if !rtok_log::enabled(gate, level) {
        return;
    }
    let errors = rtok_log::error_path(path);
    rtok_log::append_split(
        &rtok_log::FileLog {
            path,
            max_bytes,
            files,
            level: gate,
        },
        Some(&errors),
        level,
        source,
        name,
        message,
    );
}

/// Every D26 line also goes to the facade, *before* the `[log] level` gate.
fn mirror(level: &str, source: &str, name: &str, message: &str) {
    log::log!(target: STDERR_TARGET, facade_level(level), "{source}/{name}: {message}");
}

/// D26 level names onto the facade's; an unknown name ranks most severe.
fn facade_level(level: &str) -> log::Level {
    match level.to_ascii_lowercase().as_str() {
        "warn" => log::Level::Warn,
        "info" => log::Level::Info,
        "debug" => log::Level::Debug,
        _ => log::Level::Error,
    }
}
