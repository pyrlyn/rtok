// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! Binary byte sizes for status lines (`1023 B`, `1.0 KB`, `1.5 MB`).

/// `1023 B`, `1.0 KB`, `1.5 MB` — one decimal past bytes, binary units.
pub fn human_bytes(n: u64) -> String {
    const KB: f64 = 1024.0;
    let n = n as f64;
    if n < KB {
        return format!("{} B", n as u64);
    }
    for (unit, div) in [("KB", KB), ("MB", KB * KB), ("GB", KB * KB * KB)] {
        if n < div * KB {
            return format!("{:.1} {unit}", n / div);
        }
    }
    format!("{:.1} TB", n / (KB * KB * KB * KB))
}
