// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! Command and tool names shared by surfaces and plugins.
//!
//! These used to live on `agents` and `hooks`. Callers below those modules
//! (`measure`, `plugins`) need the same functions without an edge back up.

/// Basename of a command path — split on `/` and `\`, drop a trailing `.exe`
/// (case-insensitive). One definition for `agents`, `measure` and `cmd`
/// formatters (T55.10).
pub(crate) fn cmd_stem(path: &str) -> &str {
    let base = path.rsplit(['/', '\\']).next().unwrap_or(path);
    // `get`, not `[..]`: the last 4 bytes of a non-ASCII name (`héllo`) can start mid-char.
    let cut = base.len().saturating_sub(4);
    if base.len() >= 4
        && base
            .get(cut..)
            .is_some_and(|e| e.eq_ignore_ascii_case(".exe"))
    {
        &base[..cut]
    } else {
        base
    }
}

/// Host tool names (`bash`, `read_file`, `edit`, …) to the Claude names `plugins::guard` matches.
pub(crate) fn canonical_tool_name(name: &str) -> String {
    let l = name.to_ascii_lowercase();
    if l == "exec"
        || l == "run_commands"
        || ["bash", "shell", "terminal", "powershell"]
            .iter()
            .any(|k| l.contains(k))
    {
        "Bash".into()
    } else if l.starts_with("read") || l.starts_with("view") {
        "Read".into()
    } else if l == "edit" || l == "replace" || l == "edit_file" {
        "Edit".into()
    } else if l == "write" || l == "write_file" {
        "Write".into()
    } else {
        name.to_string()
    }
}
