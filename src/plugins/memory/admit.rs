// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! Admission gate for `mem_save` (T671): refuse a note that is too short to be worth a
//! recall slot or that repeats a stored one. Character trigrams and Jaccard overlap, all
//! local text — no network, no model. An embedding leg would be a second, opt-in signal.

use std::collections::HashSet;

/// How many of the newest notes a save is compared with. Bounds the work per save.
pub const WINDOW: u32 = 200;

#[derive(Debug, PartialEq, Eq)]
pub enum Verdict {
    Admit,
    TooShort,
    /// The id of the stored note this one repeats.
    Duplicate(i32),
}

fn normalize(text: &str) -> String {
    text.split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
        .to_lowercase()
}

fn trigrams(text: &str) -> HashSet<[char; 3]> {
    let chars: Vec<char> = text.chars().collect();
    chars.windows(3).map(|w| [w[0], w[1], w[2]]).collect()
}

/// Jaccard overlap of the trigram sets, as a percentage. Two equal normalized texts are 100
/// even when they are too short to hold a trigram.
fn similarity_pct(a: &str, b: &str) -> u32 {
    if a == b {
        return 100;
    }
    let (ta, tb) = (trigrams(a), trigrams(b));
    let union = ta.union(&tb).count();
    if union == 0 {
        return 0;
    }
    let pct = ta.intersection(&tb).count() * 100 / union;
    u32::try_from(pct).unwrap_or(100)
}

/// Judge a body against `(id, title, body)` rows already stored. The title is not compared:
/// the same body under a new title is still the same fact.
pub fn judge(
    min_chars: u32,
    similarity: u32,
    body: &str,
    stored: &[(i32, String, String)],
) -> Verdict {
    let body = normalize(body);
    if u32::try_from(body.chars().count()).unwrap_or(u32::MAX) < min_chars {
        return Verdict::TooShort;
    }
    stored
        .iter()
        .find(|(_, _, old)| similarity_pct(&body, &normalize(old)) >= similarity)
        .map_or(Verdict::Admit, |(id, ..)| Verdict::Duplicate(*id))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn row(id: i32, body: &str) -> (i32, String, String) {
        (id, format!("title {id}"), body.to_string())
    }

    #[test]
    fn a_distinct_note_is_admitted() {
        let stored = [row(1, "the walrus cache evicts the oldest entry first")];
        let v = judge(
            12,
            85,
            "hooks must exit zero within ten milliseconds",
            &stored,
        );
        assert_eq!(v, Verdict::Admit);
    }

    #[test]
    fn case_and_whitespace_do_not_hide_a_duplicate() {
        let stored = [row(7, "Use Diesel for every query, never raw SQL.")];
        let v = judge(
            12,
            85,
            "use   diesel for every query,\nnever raw sql.",
            &stored,
        );
        assert_eq!(v, Verdict::Duplicate(7));
    }

    #[test]
    fn a_near_duplicate_is_refused_and_a_rewrite_is_not() {
        let stored = [row(
            3,
            "Config keys come from one module and the schema from its types.",
        )];
        let near = "Config keys come from one module and the schema from its types!";
        assert_eq!(judge(12, 85, near, &stored), Verdict::Duplicate(3));
        let rewrite = "Every TOML key lives in rtok-config; the JSON schema is generated.";
        assert_eq!(judge(12, 85, rewrite, &stored), Verdict::Admit);
    }

    #[test]
    fn a_body_under_the_floor_is_refused_before_any_comparison() {
        let stored = [row(1, "ok")];
        assert_eq!(judge(12, 85, "  ok  ", &stored), Verdict::TooShort);
        assert_eq!(judge(2, 85, "ok", &stored), Verdict::Duplicate(1));
    }

    #[test]
    fn the_first_match_in_stored_order_is_named() {
        let text = "the same sentence stored twice by two sessions";
        let stored = [row(9, text), row(4, text)];
        assert_eq!(judge(12, 85, text, &stored), Verdict::Duplicate(9));
    }

    #[test]
    fn similarity_of_unrelated_texts_is_low() {
        assert!(similarity_pct("abcdefgh", "ijklmnop") < 10);
        assert_eq!(similarity_pct("", "abc"), 0);
    }
}
