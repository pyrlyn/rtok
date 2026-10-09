// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

use anyhow::Result;
use std::io::Read;

/// Lossy, because `read_to_string` empties the whole buffer on one invalid UTF-8
/// byte and the agent would get a blank tool result nothing can expand (T360).
pub(super) fn read_lossy(mut r: impl Read) -> Result<String> {
    let mut buf = Vec::new();
    r.read_to_end(&mut buf)?;
    Ok(String::from_utf8_lossy(&buf).into_owned())
}

/// A rendered diff, when there is one. An empty diff means the file was already right.
pub(super) fn print_diff(diff: &str) {
    if !diff.is_empty() {
        println!("{diff}");
    }
}

/// The report's sink (D12: `report.out`): stdout when empty, else the file. One sink
/// for every `--format` so the renderings cannot disagree about where it went.
/// Bytes, not `&str`: the PDF renderer emits binary, and the text renderings are
/// UTF-8 either way.
pub(super) fn emit(out: &std::path::Path, body: &[u8]) -> Result<()> {
    if out.as_os_str().is_empty() {
        std::io::Write::write_all(&mut std::io::stdout(), body)?;
    } else {
        std::fs::write(out, body)?;
        println!("{}", out.display());
    }
    Ok(())
}

pub(super) fn print_json(value: &(impl serde::Serialize + ?Sized)) -> Result<()> {
    println!("{}", serde_json::to_string_pretty(value)?);
    Ok(())
}
