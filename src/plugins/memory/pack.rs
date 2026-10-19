// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! Budgeted note packing for MCP `mem_pack`.
//!
//! Every hit is placed at the cheapest tier that fits, then leftover budget
//! deepens the best hits. A tier that does not fit is skipped whole. Nothing
//! here calls a model. The hook index is unchanged.

use std::collections::HashSet;

/// How much of a note `mem_pack` spent.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Tier {
    /// `{id} {title}` only.
    Uri,
    /// Title plus the search snippet.
    Abstract,
    /// Abstract plus the first paragraph of the body, at most 400 characters.
    Overview,
    /// Title plus the whole body.
    Full,
}

impl Tier {
    fn rank(self) -> u8 {
        match self {
            Tier::Uri => 0,
            Tier::Abstract => 1,
            Tier::Overview => 2,
            Tier::Full => 3,
        }
    }

    pub fn as_str(self) -> &'static str {
        match self {
            Tier::Uri => "uri",
            Tier::Abstract => "abstract",
            Tier::Overview => "overview",
            Tier::Full => "full",
        }
    }
}

/// One note chosen for the packed answer.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct PackEntry {
    pub id: i32,
    pub tier: Tier,
    pub text: String,
    pub tokens: u32,
}

/// The notes that fit, in search order.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Pack {
    pub entries: Vec<PackEntry>,
    pub used_tokens: u32,
    pub dropped: u32,
}

const OVERVIEW_CHARS: usize = 400;
const SEPARATOR: &str = "\n---\n";
/// Estimate used while packing; the final rendering is re-measured, so a coarser
/// estimator cannot push the returned text past the budget.
const SEPARATOR_TOKENS: u32 = 1;

struct Slot {
    id: i32,
    title: String,
    snippet: String,
    body: Option<String>,
    tier: Tier,
    text: String,
    tokens: u32,
}

/// `hits` are `(id, title, snippet)` already ranked. `body_of` returns the
/// stored body. `estimate` is the prose token estimator.
pub fn pack_notes(
    hits: &[(i32, String, String)],
    body_of: impl Fn(i32) -> Option<String>,
    max_tokens: u32,
    estimate: impl Fn(&str) -> u32,
) -> Pack {
    if max_tokens == 0 || hits.is_empty() {
        return Pack {
            entries: Vec::new(),
            used_tokens: 0,
            dropped: 0,
        };
    }
    let cap = (max_tokens / hits.len() as u32).saturating_mul(2).max(1);
    let mut slots: Vec<Slot> = Vec::new();
    let mut used = 0u32;
    let mut dropped = 0u32;
    let mut seen: HashSet<String> = HashSet::new();

    for (id, title, snippet) in hits {
        let body = body_of(*id);
        let mut placed = None;
        for tier in [Tier::Abstract, Tier::Uri] {
            let Some(text) = tier_text(*id, title, snippet, body.as_deref(), tier) else {
                continue;
            };
            let tokens = estimate(&entry_repr(*id, tier, &text));
            if tokens > cap {
                continue;
            }
            let sep = if slots.is_empty() {
                0
            } else {
                SEPARATOR_TOKENS
            };
            let cost = tokens.saturating_add(sep);
            if used.saturating_add(cost) > max_tokens {
                continue;
            }
            placed = Some((tier, text, tokens, cost));
            break;
        }
        let Some((tier, text, tokens, cost)) = placed else {
            dropped += 1;
            continue;
        };
        let key = format!("{title}\n{snippet}");
        if !seen.insert(key) {
            dropped += 1;
            continue;
        }
        slots.push(Slot {
            id: *id,
            title: title.clone(),
            snippet: snippet.clone(),
            body,
            tier,
            text,
            tokens,
        });
        used = used.saturating_add(cost);
    }

    upgrade(
        &mut slots,
        &mut used,
        Tier::Overview,
        true,
        cap,
        max_tokens,
        &estimate,
    );
    upgrade(
        &mut slots,
        &mut used,
        Tier::Full,
        true,
        cap,
        max_tokens,
        &estimate,
    );
    upgrade(
        &mut slots,
        &mut used,
        Tier::Full,
        false,
        cap,
        max_tokens,
        &estimate,
    );

    let mut pack = Pack {
        entries: slots
            .into_iter()
            .map(|s| PackEntry {
                id: s.id,
                tier: s.tier,
                text: s.text,
                tokens: s.tokens,
            })
            .collect(),
        used_tokens: used,
        dropped,
    };
    // The additive bookkeeping above approximates the estimate of the joined string, so
    // measure what is actually returned and shed trailing entries until it fits.
    loop {
        let measured = if pack.entries.is_empty() {
            0
        } else {
            estimate(&render(&pack))
        };
        if measured <= max_tokens {
            pack.used_tokens = measured;
            return pack;
        }
        pack.entries.pop();
        pack.dropped += 1;
    }
}

fn upgrade(
    slots: &mut [Slot],
    used: &mut u32,
    target: Tier,
    respect_cap: bool,
    cap: u32,
    max_tokens: u32,
    estimate: &impl Fn(&str) -> u32,
) {
    for slot in slots.iter_mut() {
        if slot.tier.rank() >= target.rank() {
            continue;
        }
        let Some(text) = tier_text(
            slot.id,
            &slot.title,
            &slot.snippet,
            slot.body.as_deref(),
            target,
        ) else {
            continue;
        };
        let tokens = estimate(&entry_repr(slot.id, target, &text));
        if respect_cap && tokens > cap {
            continue;
        }
        let delta = tokens as i64 - slot.tokens as i64;
        if *used as i64 + delta > max_tokens as i64 {
            continue;
        }
        slot.tier = target;
        slot.text = text;
        slot.tokens = tokens;
        *used = (*used as i64 + delta) as u32;
    }
}

fn tier_text(
    id: i32,
    title: &str,
    snippet: &str,
    body: Option<&str>,
    tier: Tier,
) -> Option<String> {
    let text = match tier {
        Tier::Uri => format!("{id} {title}"),
        Tier::Abstract => format!("{id} {title}\n{snippet}"),
        Tier::Overview => {
            let para = first_paragraph(body?)?;
            format!("{id} {title}\n{snippet}\n{para}")
        }
        Tier::Full => format!("{id} {title}\n{}", body?),
    };
    Some(text)
}

fn first_paragraph(body: &str) -> Option<String> {
    let para = body.split("\n\n").next().unwrap_or(body).trim();
    if para.is_empty() {
        return None;
    }
    let mut out = String::new();
    for ch in para.chars() {
        if out.chars().count() >= OVERVIEW_CHARS {
            break;
        }
        out.push(ch);
    }
    Some(out)
}

/// One entry as returned to the caller: `{id}\t{tier}\n{text}`. Budgeting measures this
/// form, not the bare text, so the header counts against `max_tokens`.
fn entry_repr(id: i32, tier: Tier, text: &str) -> String {
    format!("{id}\t{}\n{text}", tier.as_str())
}

/// Stable rendering: entry blocks separated by `---`.
pub fn render(pack: &Pack) -> String {
    pack.entries
        .iter()
        .map(|e| entry_repr(e.id, e.tier, &e.text))
        .collect::<Vec<_>>()
        .join(SEPARATOR)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn hit(id: i32, title: &str, snippet: &str) -> (i32, String, String) {
        (id, title.to_string(), snippet.to_string())
    }

    #[test]
    fn abstracts_fit_and_full_bodies_do_not() {
        let hits = vec![hit(1, "a", "sa"), hit(2, "b", "sb"), hit(3, "c", "sc")];
        let pack = pack_notes(
            &hits,
            |_| Some("PARA\n\nFULLBODY".into()),
            40,
            |text| {
                if text.contains("FULLBODY") {
                    500
                } else if text.contains("PARA") {
                    40
                } else {
                    10
                }
            },
        );
        assert_eq!(pack.entries.len(), 3);
        assert!(pack.entries.iter().all(|e| e.tier == Tier::Abstract));
    }

    #[test]
    fn a_huge_body_stays_at_abstract() {
        let hits = vec![hit(1, "hooks", "fail open")];
        let pack = pack_notes(
            &hits,
            |_| Some("FULLBODY".into()),
            20,
            |text| {
                if text.contains("FULLBODY") { 500 } else { 10 }
            },
        );
        assert_eq!(pack.entries.len(), 1);
        assert_eq!(pack.entries[0].tier, Tier::Abstract);
    }

    #[test]
    fn leftover_budget_deepens_only_the_first_hit() {
        let hits = vec![hit(1, "a", "sa"), hit(2, "b", "sb"), hit(3, "c", "sc")];
        let pack = pack_notes(
            &hits,
            |id| {
                if id == 1 {
                    Some("MID paragraph.\n\nFULLBODY rest".into())
                } else {
                    Some("HUGE paragraph that must not be opened.".into())
                }
            },
            50,
            |text| {
                if text.contains("FULLBODY") || text.contains("HUGE") {
                    80
                } else if text.contains("MID") {
                    20
                } else {
                    10
                }
            },
        );
        assert_eq!(pack.entries.len(), 3);
        assert_eq!(pack.entries[0].tier, Tier::Overview);
        assert_eq!(pack.entries[1].tier, Tier::Abstract);
        assert_eq!(pack.entries[2].tier, Tier::Abstract);
    }

    #[test]
    fn identical_title_and_snippet_collapse() {
        let hits = vec![hit(1, "same", "snip"), hit(2, "same", "snip")];
        let pack = pack_notes(&hits, |_| Some("body".into()), 1000, |s| s.len() as u32);
        assert_eq!(pack.entries.len(), 1);
        assert_eq!(pack.dropped, 1);
    }

    #[test]
    fn an_estimator_over_the_budget_returns_nothing() {
        let hits = vec![hit(1, "a", "b")];
        let pack = pack_notes(&hits, |_| Some("body".into()), 10, |_| 11);
        assert!(pack.entries.is_empty());
        assert_eq!(pack.dropped, 1);
    }

    fn chars4(s: &str) -> u32 {
        s.chars().count().div_ceil(4) as u32
    }

    /// T565: the header and separators the caller receives count against the budget.
    #[test]
    fn the_returned_text_never_exceeds_max_tokens() {
        let hits: Vec<_> = (1..=6)
            .map(|i| {
                hit(
                    i,
                    &format!("title {i} héllo"),
                    &format!("snippet number {i} ✓"),
                )
            })
            .collect();
        let body = |id: i32| Some(format!("first para {id}\n\nrest of the body of note {id}"));
        for max in 1..=60 {
            let pack = pack_notes(&hits, body, max, chars4);
            let text = render(&pack);
            let cost = if pack.entries.is_empty() {
                0
            } else {
                chars4(&text)
            };
            assert!(cost <= max, "max {max}: {cost} tokens in {text:?}");
            assert_eq!(
                pack.used_tokens, cost,
                "max {max}: used_tokens is the payload"
            );
        }
    }

    #[test]
    fn a_budget_too_small_for_one_complete_entry_returns_nothing() {
        let hits = vec![hit(1, "a", "b")];
        let pack = pack_notes(&hits, |_| None, 2, chars4);
        assert!(pack.entries.is_empty());
        assert_eq!((pack.used_tokens, pack.dropped), (0, 1));
        assert_eq!(render(&pack), "");
    }
}
