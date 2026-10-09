// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! `<n>`, `<n>d` or `<n>h` windows shared by `stats`, `report` and config validation.

use std::time::Duration;

use anyhow::{Result, bail};

/// `<n>`, `<n>d` or `<n>h` from the `--since` flag.
pub fn parse_since(s: &str) -> Result<Duration> {
    parse_since_from(s, "--since")
}

/// [`parse_since`] for a value read from `source` (`stats.since`, `report.since`): the error
/// names where the bad value came from, so a config typo is not blamed on a flag nobody passed.
pub fn parse_since_from(s: &str, source: &str) -> Result<Duration> {
    let s = s.trim();
    let (n, unit) = s.split_at(s.find(|c: char| !c.is_ascii_digit()).unwrap_or(s.len()));
    let n: u64 = n.parse().map_err(|_| anyhow::anyhow!("bad {source} {s}"))?;
    let per_unit = match unit {
        "" | "d" => 86_400u64,
        "h" => 3_600,
        _ => bail!("bad {source} unit in {s}"),
    };
    // `--since 99999999999999999d` used to panic in a debug build and wrap in a release one.
    let secs = n
        .checked_mul(per_unit)
        .ok_or_else(|| anyhow::anyhow!("{source} {s} is out of range"))?;
    Ok(Duration::from_secs(secs))
}
