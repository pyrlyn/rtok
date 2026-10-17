// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! `density:X` read mode (T670): keep the most informative lines until about `X` of the
//! file's bytes remain. Deterministic, no model, no I/O. A line is as informative as its
//! rarest words are rare in the file, so repeated boilerplate goes first and the lines that
//! differ from their neighbours stay. Line numbers are kept so `lines` can fetch any gap.

use std::collections::HashMap;

use anyhow::{Result, anyhow};

/// `density:0.4` → `0.4`. Anything outside `0 < x <= 1` is an error, not a silent full read.
pub(crate) fn parse(mode: &str) -> Option<Result<f64>> {
    let arg = mode.strip_prefix("density:")?;
    Some(match arg.parse::<f64>() {
        Ok(x) if x > 0.0 && x <= 1.0 => Ok(x),
        _ => Err(anyhow!("density: expected a share in (0, 1], got `{arg}`")),
    })
}

fn words(line: &str) -> impl Iterator<Item = &str> {
    line.split(|c: char| !(c.is_alphanumeric() || c == '_'))
        .filter(|w| !w.is_empty())
}

pub(crate) fn render(raw: &str, share: f64) -> String {
    let lines: Vec<&str> = raw.lines().collect();
    let total: usize = lines.iter().map(|l| l.len() + 1).sum();
    let mut freq: HashMap<&str, usize> = HashMap::new();
    let mut seen = 0usize;
    for w in lines.iter().flat_map(|l| words(l)) {
        *freq.entry(w).or_default() += 1;
        seen += 1;
    }
    // Information per byte; blank lines carry none and go first.
    let score = |l: &str| -> f64 {
        let bits: f64 = words(l)
            .map(|w| -((freq[w] as f64) / (seen as f64)).log2())
            .sum();
        bits / (l.len() + 1) as f64
    };
    let mut order: Vec<usize> = (0..lines.len()).collect();
    let scores: Vec<f64> = lines.iter().map(|l| score(l)).collect();
    order.sort_by(|&a, &b| scores[b].total_cmp(&scores[a]).then(a.cmp(&b)));
    let budget = (total as f64 * share) as usize;
    let mut keep = vec![false; lines.len()];
    let mut used = 0usize;
    for i in order {
        let cost = lines[i].len() + 1;
        if used + cost > budget {
            continue;
        }
        if scores[i] == 0.0 {
            break;
        }
        keep[i] = true;
        used += cost;
    }
    let mut out = Vec::new();
    let mut gap = 0usize;
    for (i, l) in lines.iter().enumerate() {
        if keep[i] {
            if gap > 0 {
                out.push(format!("… {gap} lines omitted"));
                gap = 0;
            }
            out.push(format!("{}:{l}", i + 1));
        } else {
            gap += 1;
        }
    }
    if gap > 0 {
        out.push(format!("… {gap} lines omitted"));
    }
    out.join("\n")
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A file shaped like real code: one unique line among repeated boilerplate.
    fn fixture() -> String {
        let mut s = String::new();
        for i in 0..60 {
            s.push_str("    let value = self.cache.get(&key).cloned();\n");
            s.push_str("    if value.is_none() { return None; }\n");
            if i == 30 {
                s.push_str("    let quota = billing::remaining_quota(account_id)?;\n");
            }
        }
        s
    }

    #[test]
    fn keeps_the_rare_line_and_drops_repeats_first() {
        let out = render(&fixture(), 0.2);
        assert!(out.contains("billing::remaining_quota"), "{out}");
        assert!(out.contains("lines omitted"), "{out}");
        assert!(out.len() < fixture().len() / 3, "{} B", out.len());
    }

    #[test]
    fn line_numbers_survive_so_lines_can_fill_a_gap() {
        let out = render(&fixture(), 0.2);
        let line = fixture()
            .lines()
            .position(|l| l.contains("billing::"))
            .unwrap()
            + 1;
        assert!(out.contains(&format!("{line}:    let quota")), "{out}");
    }

    #[test]
    fn full_share_returns_every_nonblank_line_and_is_deterministic() {
        let raw = "a b\n\nc d\n";
        assert_eq!(render(raw, 1.0), "1:a b\n… 1 lines omitted\n3:c d");
        assert_eq!(render(&fixture(), 0.3), render(&fixture(), 0.3));
    }

    #[test]
    fn parse_accepts_a_share_and_rejects_the_rest() {
        assert_eq!(parse("density:0.4").unwrap().unwrap(), 0.4);
        assert!(parse("full").is_none());
        for bad in ["density:0", "density:1.5", "density:x", "density:"] {
            assert!(parse(bad).unwrap().is_err(), "{bad}");
        }
    }
}
