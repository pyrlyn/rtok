// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! Line-level helpers for hosts whose config is YAML that the user and the host both edit
//! (Goose `config.yaml`, Continue `config.yaml`). They never re-serialise the document: a host
//! finds its own lines and swaps only those, so comments, ordering and quoting stay.

use std::fs;
use std::ops::Range;
use std::path::Path;

use anyhow::{Context, Result};

/// A scalar as YAML text: plain when that is unambiguous, else a double-quoted string (JSON's
/// escapes are valid YAML ones), so a Windows path or a path with spaces survives.
pub(super) fn scalar(s: &str) -> String {
    let plain = !s.is_empty()
        && s.chars()
            .all(|c| c.is_ascii_alphanumeric() || matches!(c, '_' | '.' | '/' | '-'));
    if plain {
        s.to_string()
    } else {
        serde_json::to_string(s).unwrap_or_else(|_| format!("\"{s}\""))
    }
}

pub(super) fn text(line: &str) -> &str {
    line.trim_end_matches(['\n', '\r'])
}

pub(super) fn indent_of(line: &str) -> usize {
    text(line).len() - text(line).trim_start_matches(' ').len()
}

pub(super) fn skippable(line: &str) -> bool {
    let t = text(line).trim_start();
    t.is_empty() || t.starts_with('#')
}

pub(super) fn read_or_empty(path: &Path) -> Result<String> {
    match fs::read_to_string(path) {
        Ok(raw) => Ok(raw),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(String::new()),
        Err(e) => Err(e).with_context(|| path.display().to_string()),
    }
}

pub(super) fn eol_of(raw: &str) -> &'static str {
    if raw.contains("\r\n") { "\r\n" } else { "\n" }
}

/// `lines` with `range` swapped for `block`; a last kept line without a newline gets one first.
pub(super) fn splice(lines: &[&str], range: Range<usize>, block: &str, eol: &str) -> String {
    let mut out: String = lines[..range.start].concat();
    if !block.is_empty() && !out.is_empty() && !out.ends_with('\n') {
        out.push_str(eol);
    }
    out.push_str(block);
    out.push_str(&lines[range.end..].concat());
    out
}
