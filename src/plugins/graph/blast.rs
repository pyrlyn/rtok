// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! T377: the `impact` walk under a token budget. A hub symbol reaches thousands of definitions;
//! the flat `depth  path  scope` listing is kept while it fits, and past the budget the rows are
//! grouped by file so the nearest and most central files lead and the cut says what was left.

use std::collections::HashMap;

use anyhow::Result;
use rtok_plugin_sdk::{Class, Ctx, Measurement};

use super::{impact_lines_text, mark_ambiguous_lines};

/// Lines of one file shown past the header. Three show what kind of caller it is; the count in
/// the header carries the rest.
const LINES_PER_FILE: usize = 3;

struct Group<'a> {
    path: &'a str,
    depth: u32,
    rows: Vec<&'a (u32, String, String)>,
}

impl Group<'_> {
    fn render(&self) -> String {
        let n = self.rows.len();
        let mut out = format!(
            "{} ({n} ref{}, depth {})\n",
            self.path,
            if n == 1 { "" } else { "s" },
            self.depth
        );
        for (d, _, scope) in self.rows.iter().take(LINES_PER_FILE) {
            let scope = if scope.is_empty() { "(file)" } else { scope };
            out.push_str(&format!("  {d}  {scope}\n"));
        }
        if n > LINES_PER_FILE {
            out.push_str(&format!("  +{} more\n", n - LINES_PER_FILE));
        }
        out
    }
}

/// What the answer may cost. The caller adds text around the rows (an ambiguity banner and
/// marks, the other-definitions line, the co-change line), so that part is taken off the budget
/// first; the finished answer then fits the configured number.
pub(crate) struct Budget {
    /// `plugins.graph.impact_tokens`; 0 means no budget.
    pub tokens: u32,
    /// Estimated tokens of the text the caller adds around the rows.
    pub overhead: u32,
    /// `--all`: keep the flat listing.
    pub all: bool,
    /// The caller marks every row line ` ?` (ambiguous name), which costs tokens per line.
    pub mark: bool,
}

impl Budget {
    #[cfg(test)]
    fn at(tokens: u32) -> Self {
        Self {
            tokens,
            overhead: 0,
            all: false,
            mark: false,
        }
    }

    pub(crate) fn new(cx: &Ctx, all: bool) -> Self {
        Self {
            tokens: cx
                .plugin_config::<crate::config::Graph>("graph")
                .impact_tokens,
            overhead: 0,
            all,
            mark: false,
        }
    }
}

/// The `impact` rows as text. `all` or a budget of 0 keeps the flat listing; so does a listing
/// that already fits, so a small answer is byte-identical to before. `rank` is read only when the
/// listing is cut: it gives each file's stored global rank, which breaks ties between files at the
/// same depth; without it, files with more references lead.
pub(crate) fn render(
    cx: &Ctx,
    rows: &[(u32, String, String)],
    name: &str,
    b: &Budget,
    rank: impl FnOnce() -> HashMap<String, f32>,
) -> Result<String> {
    let flat = impact_lines_text(rows);
    let cost = |s: &str| {
        let marked;
        let s = if b.mark {
            marked = mark_ambiguous_lines(s);
            &marked
        } else {
            s
        };
        cx.estimate(s, Class::Code)
    };
    // An overhead past the budget still leaves room for one file: the answer is never empty.
    let budget = b.tokens.saturating_sub(b.overhead).max(1);
    let flat_tokens = cost(&flat);
    if b.all || b.tokens == 0 || flat_tokens <= budget {
        return Ok(flat);
    }
    let mut by_path: HashMap<&str, Group> = HashMap::new();
    for row in rows {
        let g = by_path.entry(&row.1).or_insert_with(|| Group {
            path: &row.1,
            depth: row.0,
            rows: Vec::new(),
        });
        g.depth = g.depth.min(row.0);
        g.rows.push(row);
    }
    let rank = rank();
    let rank_of = |g: &Group| rank.get(g.path).copied().unwrap_or(0.0);
    let mut groups: Vec<Group> = by_path.into_values().collect();
    groups.sort_by(|a, b| {
        a.depth
            .cmp(&b.depth)
            .then_with(|| rank_of(b).total_cmp(&rank_of(a)))
            .then_with(|| b.rows.len().cmp(&a.rows.len()))
            .then_with(|| a.path.cmp(b.path))
    });
    let cut = |files: usize, refs: usize| {
        format!("+{files} files, {refs} refs not shown — impact {name} --all\n")
    };
    // The cut line's own size is reserved before the first file, with room for wider counts, so
    // the finished text cannot overshoot the budget by the line it appends.
    let reserve = cost(&cut(groups.len(), rows.len()));
    let mut left = budget.saturating_sub(reserve);
    let mut out = String::new();
    let mut shown = 0;
    for (i, g) in groups.iter().enumerate() {
        let block = g.render();
        let tokens = cost(&block);
        // The first file always prints: a budget below one block must not answer with nothing.
        if i > 0 && tokens > left {
            break;
        }
        left = left.saturating_sub(tokens);
        out.push_str(&block);
        shown = i + 1;
    }
    let rest = &groups[shown..];
    if !rest.is_empty() {
        out.push_str(&cut(rest.len(), rest.iter().map(|g| g.rows.len()).sum()));
    }
    // The saving is real only against the listing the model would have read otherwise.
    if out.len() < flat.len() {
        cx.record(&Measurement {
            plugin: "graph",
            kind: "impact",
            before_bytes: flat.len() as u64,
            after_bytes: out.len() as u64,
            est_before: cx.estimate(&flat, Class::Code),
            est_after: cx.estimate(&out, Class::Code),
            ref_id: None,
            call_id: cx.call_id(),
        })?;
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::super::index::tests::cx;
    use super::super::{Filter, impact_filtered};
    use super::*;
    use std::fs;

    type Rows = Vec<(u32, String, String)>;

    /// `files` callers of a hub, each with `per` definitions, all at depth 1.
    fn hub(files: usize, per: usize) -> Rows {
        (0..files)
            .flat_map(|f| {
                (0..per).map(move |d| (1, format!("src/f{f:03}.rs"), format!("fn_{f}_{d}")))
            })
            .collect()
    }

    fn text(cx: &Ctx, rows: &Rows, budget: u32, all: bool) -> String {
        let b = Budget {
            all,
            ..Budget::at(budget)
        };
        render(cx, rows, "hub", &b, HashMap::new).unwrap()
    }

    #[test]
    fn a_small_walk_is_the_flat_listing() {
        let (rt, dir) = cx("t377-small");
        let cx = Ctx::new(&rt);
        let rows = hub(3, 2);
        assert_eq!(text(&cx, &rows, 1500, false), impact_lines_text(&rows));
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn all_and_a_zero_budget_keep_the_flat_listing() {
        let (rt, dir) = cx("t377-all");
        let cx = Ctx::new(&rt);
        let rows = hub(200, 5);
        let flat = impact_lines_text(&rows);
        assert_eq!(text(&cx, &rows, 300, true), flat);
        assert_eq!(text(&cx, &rows, 0, false), flat);
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn a_hub_fits_the_budget_and_ends_with_the_cut_line() {
        let (rt, dir) = cx("t377-hub");
        let cx = Ctx::new(&rt);
        let rows = hub(200, 5);
        let out = text(&cx, &rows, 300, false);
        assert!(cx.estimate(&out, Class::Code) <= 300, "{out}");
        let shown = out.matches(" refs, depth 1)\n").count();
        assert!(shown > 0 && shown < 200, "{out}");
        let last = out.lines().last().unwrap();
        assert_eq!(
            last,
            format!(
                "+{} files, {} refs not shown — impact hub --all",
                200 - shown,
                (200 - shown) * 5
            )
        );
        // Three lines per file, the rest counted.
        assert!(
            out.starts_with(
                "src/f000.rs (5 refs, depth 1)\n  1  fn_0_0\n  1  fn_0_1\n  1  fn_0_2\n  +2 more\n"
            ),
            "{out}"
        );
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn files_lead_by_depth_then_rank_then_reference_count() {
        let (rt, dir) = cx("t377-order");
        let cx = Ctx::new(&rt);
        let mut rows: Rows = vec![
            (2, "near_but_deep.rs".into(), "a".into()),
            (1, "many.rs".into(), "a".into()),
            (1, "many.rs".into(), "b".into()),
            (1, "central.rs".into(), "a".into()),
            (1, "few.rs".into(), "a".into()),
        ];
        rows.extend(
            hub(60, 1)
                .into_iter()
                .map(|(_, p, s)| (3, format!("z/{p}"), s)),
        );
        let heads = |rank: HashMap<String, f32>| -> Vec<String> {
            render(&cx, &rows, "hub", &Budget::at(120), || rank)
                .unwrap()
                .lines()
                .filter(|l| l.contains(" (") && l.ends_with(')'))
                .map(|l| l.split(' ').next().unwrap().to_string())
                .collect()
        };
        let by_refs = heads(HashMap::new());
        assert_eq!(
            by_refs[..4],
            ["many.rs", "central.rs", "few.rs", "near_but_deep.rs"]
        );
        let ranked = heads(HashMap::from([
            ("few.rs".to_string(), 0.5),
            ("central.rs".to_string(), 0.1),
        ]));
        assert_eq!(
            ranked[..4],
            ["few.rs", "central.rs", "many.rs", "near_but_deep.rs"]
        );
        let _ = fs::remove_dir_all(dir);
    }

    /// The marks and the lines the caller adds count against the budget, so the finished answer fits.
    #[test]
    fn marks_and_overhead_come_off_the_budget() {
        let (rt, dir) = cx("t377-overhead");
        let cx = Ctx::new(&rt);
        let rows = hub(200, 5);
        let b = Budget {
            mark: true,
            overhead: 40,
            ..Budget::at(300)
        };
        let out = render(&cx, &rows, "hub", &b, HashMap::new).unwrap();
        let framed = format!("{}{}", "x".repeat(40 * 3), mark_ambiguous_lines(&out));
        assert!(cx.estimate(&framed, Class::Code) <= 300, "{framed}");
        // An overhead past the budget still prints one file and the cut line.
        let b = Budget {
            overhead: 900,
            ..Budget::at(300)
        };
        let out = render(&cx, &rows, "hub", &b, HashMap::new).unwrap();
        assert_eq!(out.matches(" refs, depth 1)\n").count(), 1, "{out}");
        assert!(out.contains("+199 files, 995 refs not shown"), "{out}");
        let _ = fs::remove_dir_all(dir);
    }

    /// The whole path: indexed callers in many files, the configured budget, and `all`.
    #[test]
    fn impact_on_an_indexed_hub_is_budgeted_and_all_lifts_it() {
        let (mut rt, dir) = cx("t377-e2e");
        fs::write(dir.join("hub.rs"), "pub fn hub() {}\n").unwrap();
        for f in 0..60 {
            let body: String = (0..4)
                .map(|d| format!("pub fn caller_{f}_{d}() {{\n    hub();\n}}\n"))
                .collect();
            fs::write(dir.join(format!("c{f:02}.rs")), body).unwrap();
        }
        rt.config.plugins.graph.impact_tokens = 200;
        let cx = Ctx::new(&rt);
        let out = impact_filtered(&cx, &dir, "hub", 1, &Filter::none(), None).unwrap();
        assert!(cx.estimate(&out, Class::Code) <= 200, "{out}");
        assert!(out.contains("c00.rs (4 refs, depth 1)\n"), "{out}");
        assert!(
            out.contains(" refs not shown — impact hub --all\n"),
            "{out}"
        );
        let all = Filter {
            all: true,
            ..Filter::none()
        };
        let full = impact_filtered(&cx, &dir, "hub", 1, &all, None).unwrap();
        assert_eq!(full.matches("caller_").count(), 240, "{full}");
        assert!(!full.contains("not shown"), "{full}");
        let _ = fs::remove_dir_all(dir);
    }
}
