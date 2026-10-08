// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! Breadth-first then deepen: place every hit at a cheap tier, then spend leftover
//! budget upgrading the best hits. Original packing rule for MCP `mem_pack`.

/// Separator between rendered blocks costs one estimated token while packing.
const SEPARATOR_TOKENS: u32 = 1;

/// How much of a note to show inside the budget.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub enum Tier {
    Uri,
    Abstract,
    Overview,
    Full,
}

impl Tier {
    pub fn as_str(self) -> &'static str {
        match self {
            Tier::Uri => "uri",
            Tier::Abstract => "abstract",
            Tier::Overview => "overview",
            Tier::Full => "full",
        }
    }
}

/// One note after packing, at the tier that fit.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PackEntry {
    pub id: i32,
    pub tier: Tier,
    pub text: String,
    pub tokens: u32,
}

/// Packed notes plus how much of the budget was used and how many hits were dropped.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct Pack {
    pub entries: Vec<PackEntry>,
    pub used_tokens: u32,
    pub dropped: u32,
}

struct Slot {
    id: i32,
    title: String,
    snippet: String,
    entry: PackEntry,
}

/// `hits` are already ranked (FTS5 or the existing hybrid path).
/// `body_of` returns the stored body. `estimate` is Ctx::estimate(., Prose).
pub fn pack_notes(
    hits: &[(i32, String, String)],
    body_of: impl Fn(i32) -> Option<String>,
    max_tokens: u32,
    estimate: impl Fn(&str) -> u32,
) -> Pack {
    if max_tokens == 0 {
        return Pack::default();
    }
    let cap = (max_tokens / hits.len().max(1) as u32)
        .saturating_mul(2)
        .max(1);
    let mut slots: Vec<Slot> = Vec::new();
    let mut used = 0u32;
    let mut dropped = 0u32;
    // Dedup key is the chosen text with the leading "{id} " stripped, so two
    // hits that only differ by note id (same title + snippet) collapse. A
    // ledger-style failure path is not wired here: in-memory dedup cannot fail
    // the call (degrade to no dedup would be a no-op empty set).
    let mut seen: std::collections::HashSet<String> = std::collections::HashSet::new();

    for (id, title, snippet) in hits {
        let mut placed: Option<PackEntry> = None;
        for tier in [Tier::Abstract, Tier::Uri] {
            let Some(text) = tier_text(*id, title, snippet, tier, &body_of) else {
                continue;
            };
            let tokens = estimate(&text);
            if tokens > cap {
                continue;
            }
            let cost = tokens
                + if slots.is_empty() {
                    0
                } else {
                    SEPARATOR_TOKENS
                };
            if used.saturating_add(cost) > max_tokens {
                continue;
            }
            placed = Some(PackEntry {
                id: *id,
                tier,
                text,
                tokens,
            });
            break;
        }
        let Some(entry) = placed else {
            dropped += 1;
            continue;
        };
        let key = dedup_key(&entry.text);
        if !seen.insert(key) {
            dropped += 1;
            continue;
        }
        used = used.saturating_add(
            entry.tokens
                + if slots.is_empty() {
                    0
                } else {
                    SEPARATOR_TOKENS
                },
        );
        slots.push(Slot {
            id: *id,
            title: title.clone(),
            snippet: snippet.clone(),
            entry,
        });
    }

    let mut budget = Budget {
        used,
        cap,
        max_tokens,
    };
    upgrade_pass(
        &mut slots,
        &mut budget,
        &body_of,
        &estimate,
        Tier::Overview,
        true,
    );
    upgrade_pass(
        &mut slots,
        &mut budget,
        &body_of,
        &estimate,
        Tier::Full,
        true,
    );
    // Leftover budget: one more Full pass without the per-entry cap.
    upgrade_pass(
        &mut slots,
        &mut budget,
        &body_of,
        &estimate,
        Tier::Full,
        false,
    );

    Pack {
        entries: slots.into_iter().map(|s| s.entry).collect(),
        used_tokens: budget.used,
        dropped,
    }
}

struct Budget {
    used: u32,
    cap: u32,
    max_tokens: u32,
}

fn upgrade_pass(
    slots: &mut [Slot],
    budget: &mut Budget,
    body_of: &impl Fn(i32) -> Option<String>,
    estimate: &impl Fn(&str) -> u32,
    target: Tier,
    respect_cap: bool,
) {
    for slot in slots.iter_mut() {
        if slot.entry.tier >= target {
            continue;
        }
        let Some(text) = tier_text(slot.id, &slot.title, &slot.snippet, target, body_of) else {
            continue;
        };
        let tokens = estimate(&text);
        if respect_cap && tokens > budget.cap {
            continue;
        }
        let delta = tokens.saturating_sub(slot.entry.tokens);
        if budget.used.saturating_add(delta) > budget.max_tokens {
            continue;
        }
        budget.used = budget.used.saturating_add(delta);
        slot.entry = PackEntry {
            id: slot.id,
            tier: target,
            text,
            tokens,
        };
    }
}

fn tier_text(
    id: i32,
    title: &str,
    snippet: &str,
    tier: Tier,
    body_of: &impl Fn(i32) -> Option<String>,
) -> Option<String> {
    match tier {
        Tier::Uri => Some(format!("{id} {title}")),
        Tier::Abstract => Some(format!("{id} {title}\n{snippet}")),
        Tier::Overview => {
            let body = body_of(id)?;
            let para = first_paragraph(&body, 400);
            if para.is_empty() {
                return None;
            }
            Some(format!("{id} {title}\n{snippet}\n{para}"))
        }
        Tier::Full => {
            let body = body_of(id)?;
            Some(format!("{id} {title}\n{body}"))
        }
    }
}

/// First paragraph of `body`, at most `max_chars` Unicode scalars (char boundary).
fn first_paragraph(body: &str, max_chars: usize) -> String {
    let para = match body.split_once("\n\n") {
        Some((head, _)) => head,
        None => body,
    };
    para.chars().take(max_chars).collect()
}

/// Strip the leading `{id} ` so identical title/snippet bodies collide.
fn dedup_key(text: &str) -> String {
    match text.find(' ') {
        Some(i) => text[i + 1..].to_string(),
        None => text.to_string(),
    }
}

/// Stable render for a packed hit list: `{id}\t{tier}\n{text}` joined by `\\n---\\n`.
pub fn render_pack(pack: &Pack) -> String {
    pack.entries
        .iter()
        .map(|e| format!("{}\t{}\n{}", e.id, e.tier.as_str(), e.text))
        .collect::<Vec<_>>()
        .join("\n---\n")
}

#[cfg(test)]
mod tests {
    use super::*;

    fn hits(rows: &[(&str, &str)]) -> Vec<(i32, String, String)> {
        rows.iter()
            .enumerate()
            .map(|(i, (title, snippet))| (i as i32 + 1, (*title).into(), (*snippet).into()))
            .collect()
    }

    /// Token estimate = character count, so budgets are easy to size in tests.
    fn chars(s: &str) -> u32 {
        s.len() as u32
    }

    #[test]
    fn pack_notes_three_hits_fit_as_abstracts_when_budget_is_tight() {
        let h = hits(&[
            ("one", "snip one"),
            ("two", "snip two"),
            ("three", "snip three"),
        ]);
        // Abstracts are ~18–20 chars; three with separators need ~60. Cap is
        // (60/3)*2 = 40, so abstracts fit and full bodies (hundreds) do not.
        let bodies: std::collections::HashMap<i32, String> = [
            (1, "x".repeat(200)),
            (2, "y".repeat(200)),
            (3, "z".repeat(200)),
        ]
        .into_iter()
        .collect();
        let pack = pack_notes(&h, |id| bodies.get(&id).cloned(), 60, chars);
        assert_eq!(pack.entries.len(), 3);
        assert!(pack.entries.iter().all(|e| e.tier == Tier::Abstract));
        assert!(pack.entries.iter().all(|e| e.tier != Tier::Full));
    }

    #[test]
    fn pack_notes_huge_body_stays_abstract_when_full_misses_the_budget() {
        let h = hits(&[("hooks", "fail open")]);
        let body = "B".repeat(500);
        let pack = pack_notes(&h, |_| Some(body.clone()), 40, chars);
        assert_eq!(pack.entries.len(), 1);
        assert_eq!(pack.entries[0].tier, Tier::Abstract);
        assert!(!pack.entries[0].text.contains(&body));
    }

    #[test]
    fn pack_notes_leftover_budget_deepens_only_the_first_hit() {
        let h = hits(&[("a", "sa"), ("b", "sb"), ("c", "sc")]);
        // Short abstracts (~6 chars). After three abstracts + 2 seps ≈ 20, leave
        // enough for one overview (~abstract + 10-char para) but not three.
        let bodies: std::collections::HashMap<i32, String> = [
            (1, "AAAAAAAAAA".to_string()),
            (2, "BBBBBBBBBB".to_string()),
            (3, "CCCCCCCCCC".to_string()),
        ]
        .into_iter()
        .collect();
        let pack = pack_notes(&h, |id| bodies.get(&id).cloned(), 35, chars);
        assert_eq!(pack.entries.len(), 3);
        assert!(
            pack.entries[0].tier > Tier::Abstract,
            "first deepens: {:?}",
            pack.entries[0].tier
        );
        assert_eq!(pack.entries[1].tier, Tier::Abstract);
        assert_eq!(pack.entries[2].tier, Tier::Abstract);
    }

    #[test]
    fn pack_notes_identical_chosen_text_collapses_to_one() {
        let h = vec![
            (1, "same".into(), "snip".into()),
            (2, "same".into(), "snip".into()),
        ];
        let pack = pack_notes(&h, |_| Some("body".into()), 200, chars);
        assert_eq!(pack.entries.len(), 1);
        assert_eq!(pack.dropped, 1);
    }

    #[test]
    fn pack_notes_estimate_above_budget_yields_empty_pack_not_panic() {
        let h = hits(&[("t", "s")]);
        let pack = pack_notes(&h, |_| Some("body".into()), 10, |_| 11);
        assert!(pack.entries.is_empty());
        assert_eq!(pack.dropped, 1);
        assert_eq!(pack.used_tokens, 0);
    }
}
