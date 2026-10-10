// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! The web Hosts page's junk card and its "clear safe junk" button (T330.7). The card is the
//! numbers `agents junk list` prints, as data. The button is `agents junk clear` with every
//! agent named and nothing else: `plan` is its dry run and writes nothing, `apply` is
//! `--yes` through the same `junk_clear` plan, re-checks and refusals. Like the doctor page,
//! it keeps no state between the two: `apply` plans again and removes only the paths the
//! page showed and the user confirmed.

use schemars::JsonSchema;
use serde::Serialize;

use super::HOSTS;
use super::junk::Report;
use super::junk_clear::{self, Cleared, Filter};
use crate::config::Config;

/// One junk kind under an agent.
#[derive(Debug, Clone, Serialize, JsonSchema)]
pub struct CardKind {
    pub kind: String,
    /// `safe`, `review`, `explicit` or `never` (the T330 classes).
    pub class: String,
    pub items: usize,
    pub size_bytes: u64,
}

#[derive(Debug, Clone, Serialize, JsonSchema)]
pub struct CardAgent {
    pub name: String,
    pub total_bytes: u64,
    pub kinds: Vec<CardKind>,
    /// "Freed by `clear`".
    pub freed_default_bytes: u64,
    /// "Freed with `--include review`".
    pub freed_review_bytes: u64,
}

/// The `junk` card of the Hosts page.
#[derive(Debug, Clone, Serialize, JsonSchema)]
pub struct JunkCard {
    pub agents: Vec<CardAgent>,
    pub total_bytes: u64,
    pub freed_default_bytes: u64,
    pub freed_review_bytes: u64,
}

impl From<&Report> for JunkCard {
    fn from(report: &Report) -> Self {
        // `list` prints the "Freed" lines for rtok and for a host with something to clear;
        // a host that only has folders is already a block of the page.
        let shown = report
            .agents
            .iter()
            .filter(|a| !a.host || !a.kinds.is_empty());
        JunkCard {
            agents: shown
                .map(|a| CardAgent {
                    name: a.name.to_string(),
                    total_bytes: a.total_bytes,
                    kinds: a
                        .kinds
                        .iter()
                        .map(|k| CardKind {
                            kind: k.kind.to_string(),
                            class: k.class.to_string(),
                            items: k.items,
                            size_bytes: k.size_bytes,
                        })
                        .collect(),
                    freed_default_bytes: a.freed_default_bytes,
                    freed_review_bytes: a.freed_review_bytes,
                })
                .collect(),
            total_bytes: report.total_bytes,
            freed_default_bytes: report.freed_default_bytes,
            freed_review_bytes: report.freed_review_bytes,
        }
    }
}

/// What the button clears: the `safe` kinds of every agent. A bare `clear` is T182's rtok-only
/// run, so the agents are named, as `--agent` does; no `--kind`, `--include review` or
/// `--trash`, so `review`, `explicit` and `never` kinds are out of reach of the page.
fn filter() -> Filter {
    Filter {
        agents: std::iter::once("rtok")
            .chain(HOSTS.iter().copied())
            .map(String::from)
            .collect(),
        ..Filter::default()
    }
}

/// The dry run over `report`; nothing is removed.
pub fn plan(cfg: &Config, report: &Report) -> Cleared {
    junk_clear::run_in(cfg, report, &filter(), false, None)
}

/// Remove what `paths` names out of a fresh plan. The caller sends this only for the page's
/// explicit confirmation.
pub fn apply(cfg: &Config, report: &Report, paths: &[String]) -> Cleared {
    junk_clear::run_in(cfg, report, &filter(), true, Some(paths))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::agents::junk::{AGENT_SCAN_LIMIT, Options, report_with};
    use crate::agents::junk_cache::age_files;
    use crate::agents::junk_map::Roots;

    const DAY: u64 = 86_400;

    /// A report over a fixture home with one stale rtok log generation, aged past the
    /// one-minute settle so `apply` may take it.
    fn fixture(tag: &str) -> (Config, Report, std::path::PathBuf) {
        let (mut cfg, dir) = crate::testutil::config(tag);
        cfg.log.files = 1;
        let stale = dir.join("rtok.log.7");
        std::fs::write(&stale, b"stale").unwrap();
        age_files(&dir, 2 * DAY);
        let roots = Roots::new(dir.clone(), |_| None);
        let report = report_with(&cfg, &roots, Options::default(), AGENT_SCAN_LIMIT);
        (cfg, report, stale)
    }

    fn paths(c: &Cleared) -> Vec<&str> {
        c.items.iter().map(|p| p.path.as_str()).collect()
    }

    #[test]
    fn the_card_carries_rtok_kinds_and_the_freed_lines() {
        let (_cfg, report, _stale) = fixture("junk-web-card");
        let card = JunkCard::from(&report);
        let rtok = card.agents.iter().find(|a| a.name == "rtok").unwrap();
        let log = rtok.kinds.iter().find(|k| k.kind == "log").unwrap();
        assert_eq!(
            (log.class.as_str(), log.items, log.size_bytes),
            ("safe", 1, 5)
        );
        assert_eq!(
            rtok.freed_default_bytes,
            report.agents[0].freed_default_bytes
        );
        assert_eq!(card.freed_default_bytes, report.freed_default_bytes);
        assert!(
            card.agents
                .iter()
                .all(|a| a.name == "rtok" || !a.kinds.is_empty())
        );
    }

    #[test]
    fn a_plan_lists_the_item_and_writes_nothing() {
        let (cfg, report, stale) = fixture("junk-web-plan");
        let plan = plan(&cfg, &report);
        assert!(!plan.yes && plan.freed_bytes == 0);
        assert_eq!(paths(&plan), [stale.to_str().unwrap()]);
        assert!(plan.items[0].planned && plan.planned_bytes == 5);
        assert!(stale.exists());
    }

    #[test]
    fn apply_removes_only_the_confirmed_paths() {
        let (cfg, report, stale) = fixture("junk-web-apply");
        let none = apply(&cfg, &report, &["/somewhere/else".to_string()]);
        assert!(none.items.is_empty() && none.freed_bytes == 0);
        assert!(stale.exists(), "an unconfirmed item was removed");

        let done = apply(&cfg, &report, &[stale.display().to_string()]);
        assert!(done.yes && !done.failed(), "{done:?}");
        assert_eq!(
            (done.freed_bytes, done.items[0].note.as_str()),
            (5, "removed")
        );
        assert!(!stale.exists());
    }

    /// The button's reach is the `safe` class: a review kind of the same agent stays.
    #[test]
    fn the_filter_names_every_agent_and_no_kind_or_review() {
        let f = filter();
        assert!(!f.is_t182() && f.kinds.is_empty() && !f.include_review && !f.trash);
        assert!(HOSTS.iter().all(|h| f.agents.iter().any(|a| a == h)));
        assert!(f.agents.iter().any(|a| a == "rtok"));
    }
}
