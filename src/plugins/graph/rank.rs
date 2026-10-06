// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! T370: the SessionStart repo map ranked by file-level personalized PageRank.
//!
//! An edge A to B means file A references a name that file B defines, so rank flows to the
//! files the rest of the tree leans on. The graph and its global ranks are built when an index
//! run changes the root and stored as one document (`file_rank`); the hook reads that row and
//! never scans `symbols` or spawns a process. A personalized rank is computed in memory from the
//! stored edges and is never written back: a stored rank that carried one session's focus would
//! steer the next session's map.
//!
//! Clean-room: Empryo's `repo-map.ts` was read for the idea (BSL 1.1), no code is taken.

use std::collections::{BTreeMap, HashMap};

use anyhow::Result;
use rtok_plugin_sdk::{Class, Ctx};
use serde::{Deserialize, Serialize};

const DAMPING: f64 = 0.85;
const MAX_ITERATIONS: usize = 20;
/// L1 change between two iterations under which the vector counts as settled.
const EPSILON: f64 = 1e-6;
/// Definitions listed next to a file in the map.
const TOP_DEFS: usize = 2;
/// A file edited this recently (nanoseconds, matches the index's mtime) is part of what the
/// session is about.
const RECENT_NANOS: i64 = 24 * 3600 * 1_000_000_000;
/// More recent files than this means a fresh clone or a checkout, not a working set; seeding
/// them all would personalize the map toward noise.
const MAX_SEEDS: usize = 32;
/// Bumped when the document's shape changes; an older document reads as absent.
const VERSION: u32 = 1;

/// The stored document: files by index, the global rank of each and the weighted edges, sorted
/// by source file.
#[derive(Debug, Default, Deserialize, Serialize, PartialEq)]
pub struct FileGraph {
    v: u32,
    paths: Vec<String>,
    /// Indexed mtime in nanoseconds, 0 when unknown.
    mtimes: Vec<i64>,
    /// Up to [`TOP_DEFS`] definition names per file, most referenced first.
    defs: Vec<Vec<String>>,
    rank: Vec<f32>,
    src: Vec<u32>,
    dst: Vec<u32>,
    weight: Vec<f32>,
}

/// Build the graph from `(name, path, is_def, rows)` scan rows (one per name and file) and the
/// indexed files with their mtime. Deterministic: the same index gives the same document.
pub fn build(scan: &[(String, String, bool, i64)], mtimes: &HashMap<String, i64>) -> FileGraph {
    let mut ids: BTreeMap<&str, u32> = BTreeMap::new();
    for path in mtimes
        .keys()
        .map(String::as_str)
        .chain(scan.iter().map(|r| r.1.as_str()))
    {
        ids.entry(path).or_default();
    }
    let paths: Vec<String> = ids.keys().map(|p| (*p).to_string()).collect();
    for (i, id) in ids.values_mut().enumerate() {
        *id = i as u32;
    }
    let n = paths.len();
    let mut def_files: BTreeMap<&str, Vec<u32>> = BTreeMap::new();
    let mut ref_files: BTreeMap<&str, Vec<(u32, i64)>> = BTreeMap::new();
    let mut ref_total: HashMap<&str, i64> = HashMap::new();
    for (name, path, is_def, count) in scan {
        let id = ids[path.as_str()];
        if *is_def {
            def_files.entry(name).or_default().push(id);
        } else {
            ref_files.entry(name).or_default().push((id, *count));
            *ref_total.entry(name).or_default() += count;
        }
    }
    let mut edges: BTreeMap<(u32, u32), f64> = BTreeMap::new();
    for (name, referrers) in &ref_files {
        let Some(definers) = def_files.get(name) else {
            continue;
        };
        // A name referenced from most files says little about any one of them; the definers
        // split what is left.
        let idf = (1.0 + n as f64 / referrers.len() as f64).ln();
        for &(from, count) in referrers {
            for &to in definers.iter().filter(|&&to| to != from) {
                *edges.entry((from, to)).or_default() += count as f64 * idf / definers.len() as f64;
            }
        }
    }
    let mut defs: Vec<Vec<(i64, &str)>> = vec![Vec::new(); n];
    for (name, definers) in &def_files {
        let refs = ref_total.get(name).copied().unwrap_or(0);
        for &id in definers {
            defs[id as usize].push((-refs, name));
        }
    }
    let defs = defs
        .into_iter()
        .map(|mut d| {
            d.sort_unstable();
            d.into_iter()
                .take(TOP_DEFS)
                .map(|(_, name)| name.to_string())
                .collect()
        })
        .collect();
    let mut g = FileGraph {
        v: VERSION,
        mtimes: paths
            .iter()
            .map(|p| mtimes.get(p).copied().unwrap_or(0))
            .collect(),
        paths,
        defs,
        rank: Vec::new(),
        src: edges.keys().map(|e| e.0).collect(),
        dst: edges.keys().map(|e| e.1).collect(),
        weight: edges.values().map(|w| *w as f32).collect(),
    };
    g.rank = g.pagerank(&[]).into_iter().map(|r| r as f32).collect();
    g
}

impl FileGraph {
    /// PageRank by power iteration. `seeds` (file indexes) are where the random walk restarts;
    /// none means every file equally, which is the stored global rank. Mass of a file with no
    /// outgoing edge is spread uniformly, so the vector always sums to 1.
    pub fn pagerank(&self, seeds: &[u32]) -> Vec<f64> {
        self.power_iteration(seeds, MAX_ITERATIONS, EPSILON)
    }

    fn power_iteration(&self, seeds: &[u32], max_iterations: usize, epsilon: f64) -> Vec<f64> {
        let n = self.paths.len();
        if n == 0 {
            return Vec::new();
        }
        let mut restart = vec![0.0; n];
        if seeds.is_empty() {
            restart.fill(1.0 / n as f64);
        } else {
            for &s in seeds {
                restart[s as usize] += 1.0 / seeds.len() as f64;
            }
        }
        let mut out_weight = vec![0.0f64; n];
        for (&s, &w) in self.src.iter().zip(&self.weight) {
            out_weight[s as usize] += f64::from(w);
        }
        let mut rank = restart.clone();
        for _ in 0..max_iterations {
            let dangling: f64 = (0..n)
                .filter(|&i| out_weight[i] == 0.0)
                .map(|i| rank[i])
                .sum();
            let spread = DAMPING * dangling / n as f64;
            let mut next: Vec<f64> = restart
                .iter()
                .map(|r| (1.0 - DAMPING) * r + spread)
                .collect();
            for ((&s, &d), &w) in self.src.iter().zip(&self.dst).zip(&self.weight) {
                next[d as usize] +=
                    DAMPING * rank[s as usize] * f64::from(w) / out_weight[s as usize];
            }
            let delta: f64 = next.iter().zip(&rank).map(|(a, b)| (a - b).abs()).sum();
            rank = next;
            if delta < epsilon {
                break;
            }
        }
        rank
    }

    /// Files edited within a day of `now_nanos`, when that is a working set. Empty otherwise.
    fn recent(&self, now_nanos: i64) -> Vec<u32> {
        let recent: Vec<u32> = (0..self.paths.len() as u32)
            .filter(|&i| {
                let m = self.mtimes[i as usize];
                m > 0 && now_nanos - m < RECENT_NANOS
            })
            .collect();
        if recent.len() > MAX_SEEDS {
            Vec::new()
        } else {
            recent
        }
    }

    fn index_of(&self, rel: &str) -> Option<u32> {
        self.paths
            .binary_search_by(|p| p.as_str().cmp(rel))
            .ok()
            .map(|i| i as u32)
    }

    /// The map: files by rank, each with its top definitions, filled in one pass against a
    /// running token estimate. A file that defines nothing is not listed.
    fn render(&self, rank: &[f64], cap: u32, cx: &Ctx) -> Option<String> {
        let mut order: Vec<usize> = (0..self.paths.len())
            .filter(|&i| !self.defs[i].is_empty())
            .collect();
        order.sort_by(|&a, &b| {
            rank[b]
                .total_cmp(&rank[a])
                .then(self.paths[a].cmp(&self.paths[b]))
        });
        let mut text = String::from("repo map\n");
        let mut used = cx.estimate(&text, Class::Prose);
        let mut files = 0;
        for i in order {
            let line = format!("{}: {}\n", self.paths[i], self.defs[i].join(", "));
            used += cx.estimate(&line, Class::Prose);
            if used > cap {
                break;
            }
            text.push_str(&line);
            files += 1;
        }
        (files > 0).then(|| text.trim_end().to_string())
    }
}

/// Rebuild and store the root's graph from the index. Called after an index run that changed
/// something, never on the hook path.
pub fn refresh(cx: &Ctx, root: &str) -> Result<()> {
    let mtimes = cx
        .symbol_stats(root)?
        .into_iter()
        .map(|(path, (_, mtime, _))| (path, mtime))
        .collect();
    let g = build(&cx.symbol_file_scan(root)?, &mtimes);
    cx.file_rank_put(root, &serde_json::to_string(&g)?)
}

/// Whether the root has no stored graph yet, as after an upgrade over an index that is current.
pub fn missing(cx: &Ctx, root: &str) -> bool {
    cx.file_rank_get(root).ok().flatten().is_none()
}

/// The SessionStart map of `root`: ranked from the stored graph, personalized by recently
/// edited files and, when the session was compacted, the files its last checkpoint named.
/// `None` when no graph is stored, so the caller falls back.
pub fn map(
    cx: &Ctx,
    root: &str,
    cap: u32,
    checkpoint_paths: &[String],
    now_nanos: i64,
) -> Option<String> {
    let g: FileGraph = serde_json::from_str(&cx.file_rank_get(root).ok()??).ok()?;
    if g.v != VERSION {
        return None;
    }
    let mut seeds = g.recent(now_nanos);
    seeds.extend(checkpoint_paths.iter().filter_map(|p| g.index_of(p)));
    seeds.sort_unstable();
    seeds.dedup();
    let rank = if seeds.is_empty() {
        g.rank.iter().map(|r| f64::from(*r)).collect()
    } else {
        g.pagerank(&seeds)
    };
    g.render(&rank, cap, cx)
}

#[cfg(test)]
mod tests {
    use super::super::index::tests::cx;
    use super::*;

    /// Long after the fixtures' mtime of 1 ns, so no file counts as recently edited.
    const NOW: i64 = 100 * RECENT_NANOS;

    fn graph(n: usize, edges: &[(u32, u32)]) -> FileGraph {
        FileGraph {
            v: VERSION,
            paths: (0..n).map(|i| format!("f{i}.rs")).collect(),
            mtimes: vec![0; n],
            defs: vec![vec!["d".into()]; n],
            rank: Vec::new(),
            src: edges.iter().map(|e| e.0).collect(),
            dst: edges.iter().map(|e| e.1).collect(),
            weight: vec![1.0; edges.len()],
        }
    }

    fn row(name: &str, path: &str, is_def: bool, n: i64) -> (String, String, bool, i64) {
        (name.into(), path.into(), is_def, n)
    }

    /// Three leaves point at a hub and the hub points back at each, so the stationary vector has a
    /// closed form: hub `(1 + 3d) / (4 (1 + d))`, each leaf `(1 - hub) / 3`.
    #[test]
    fn four_node_graph_converges_to_the_known_vector() {
        let g = graph(4, &[(1, 0), (2, 0), (3, 0), (0, 1), (0, 2), (0, 3)]);
        let hub = (1.0 + 3.0 * DAMPING) / (4.0 * (1.0 + DAMPING));
        let leaf = (1.0 - hub) / 3.0;
        let exact = g.power_iteration(&[], 1000, 1e-15);
        assert!((exact.iter().sum::<f64>() - 1.0).abs() < 1e-9);
        assert!((exact[0] - hub).abs() < 1e-9, "{exact:?}");
        assert!(
            exact[1..].iter().all(|r| (r - leaf).abs() < 1e-9),
            "{exact:?}"
        );
        // The shipped 20 iterations stop short of the limit but keep the order and the mass.
        let shipped = g.pagerank(&[]);
        assert!((shipped.iter().sum::<f64>() - 1.0).abs() < 1e-9);
        assert!((shipped[0] - hub).abs() < 0.02, "{shipped:?}");
    }

    #[test]
    fn dangling_mass_is_spread_and_an_empty_graph_is_empty() {
        let g = graph(3, &[]);
        assert!(
            g.pagerank(&[])
                .iter()
                .all(|r| (r - 1.0 / 3.0).abs() < 1e-12)
        );
        assert!(graph(0, &[]).pagerank(&[]).is_empty());
    }

    #[test]
    fn seeds_pull_rank_toward_themselves_and_keep_the_mass() {
        // 0 -> 1 -> 2, and 3 -> 2: file 3 is nobody's target.
        let g = graph(4, &[(0, 1), (1, 2), (3, 2)]);
        let global = g.pagerank(&[]);
        let seeded = g.pagerank(&[3]);
        assert!((seeded.iter().sum::<f64>() - 1.0).abs() < 1e-9);
        assert!(seeded[3] > global[3] * 1.5, "{global:?} {seeded:?}");
        assert!(seeded[0] < global[0], "{global:?} {seeded:?}");
        assert_eq!(g.pagerank(&[3]), seeded, "same seeds, same bytes");
    }

    #[test]
    fn rank_flows_to_the_defining_file_and_common_names_weigh_less() {
        let mtimes = ["a.rs", "b.rs", "c.rs", "d.rs"]
            .map(|p| (p.to_string(), 0))
            .into_iter()
            .collect();
        let scan = [
            // `core` is defined in d.rs and used by every other file; `local` only by a.rs.
            row("core", "d.rs", true, 1),
            row("core", "a.rs", false, 1),
            row("core", "b.rs", false, 1),
            row("core", "c.rs", false, 1),
            row("local", "b.rs", true, 1),
            row("local", "a.rs", false, 4),
            // A name defined nowhere and a name a file uses inside its own file are not edges.
            row("external", "a.rs", false, 9),
            row("own", "c.rs", true, 1),
            row("own", "c.rs", false, 3),
        ];
        let g = build(&scan, &mtimes);
        assert_eq!(g.paths, ["a.rs", "b.rs", "c.rs", "d.rs"]);
        let edges: Vec<(u32, u32)> = g.src.iter().copied().zip(g.dst.iter().copied()).collect();
        assert_eq!(edges, [(0, 1), (0, 3), (1, 3), (2, 3)]);
        assert!(
            g.weight[0] > g.weight[1],
            "four `local` refs outweigh one `core` ref: {:?}",
            g.weight
        );
        let rank = &g.rank;
        assert!((rank.iter().map(|r| f64::from(*r)).sum::<f64>() - 1.0).abs() < 1e-6);
        assert!(rank[3] > rank[1] && rank[1] > rank[0], "{rank:?}");
        assert_eq!(g.defs[3], ["core"]);
        assert_eq!(build(&scan, &mtimes), g, "deterministic");
    }

    #[test]
    fn a_name_in_every_file_is_down_weighted_below_a_rare_one() {
        let paths = ["a.rs", "b.rs", "c.rs", "d.rs"];
        let mtimes = paths.map(|p| (p.to_string(), 0)).into_iter().collect();
        let mut scan = vec![row("common", "a.rs", true, 1), row("rare", "b.rs", true, 1)];
        scan.extend(paths.iter().map(|p| row("common", p, false, 1)));
        scan.push(row("rare", "c.rs", false, 1));
        let g = build(&scan, &mtimes);
        let weight = |from: u32, to: u32| {
            let i = (0..g.src.len()).find(|&i| (g.src[i], g.dst[i]) == (from, to));
            g.weight[i.expect("edge")]
        };
        assert!(weight(2, 1) > weight(2, 0), "{:?}", g.weight);
    }

    #[test]
    fn recent_files_seed_only_a_working_set() {
        let day = RECENT_NANOS;
        let now = 100 * day;
        let mut g = graph(40, &[]);
        g.mtimes[5] = now - day / 2;
        g.mtimes[7] = now - 2 * day;
        assert_eq!(g.recent(now), [5]);
        g.mtimes = vec![now - 1; 40];
        assert!(g.recent(now).is_empty(), "a fresh checkout seeds nothing");
        assert!(graph(3, &[]).recent(now).is_empty(), "mtime 0 is unknown");
    }

    fn seed_rows(rt: &crate::plugin::Runtime, root: &str) {
        let def = |n: &str, l| {
            (
                n.to_string(),
                "function".to_string(),
                l,
                true,
                l,
                String::new(),
            )
        };
        let usage = |n: &str, l| {
            (
                n.to_string(),
                "function".to_string(),
                l,
                false,
                l,
                String::new(),
            )
        };
        let put = |path: &str, rows: &[_]| {
            rt.store
                .replace_symbols(root, path, "s", (1, 1), rows)
                .unwrap();
        };
        put("core.rs", &[def("core_fn", 1), def("core_other", 2)]);
        put("util.rs", &[def("util_fn", 1), usage("core_fn", 5)]);
        put(
            "app.rs",
            &[def("app_main", 1), usage("core_fn", 3), usage("util_fn", 4)],
        );
        put("ext.rs", &[def("ext_fn", 1)]);
        put(
            "side.rs",
            &[def("side_fn", 1), usage("app_main", 2), usage("ext_fn", 3)],
        );
    }

    fn listed(text: &str) -> Vec<&str> {
        text.lines()
            .skip(1)
            .filter_map(|l| l.split(':').next())
            .collect()
    }

    #[test]
    fn the_stored_graph_never_holds_session_state() {
        let (rt, dir) = cx("rank-store");
        let root = "/rank/store";
        seed_rows(&rt, root);
        let c = Ctx::new(&rt);
        assert!(missing(&c, root));
        refresh(&c, root).unwrap();
        assert!(!missing(&c, root));
        let stored = rt.store.file_rank_get(root).unwrap().unwrap();
        let global = map(&c, root, 500, &[], NOW).unwrap();
        assert_eq!(listed(&global)[0], "core.rs", "{global}");
        let focused = map(&c, root, 500, &["side.rs".to_string()], NOW).unwrap();
        assert_ne!(global, focused, "a checkpoint path moves the map");
        let at = |text: &str| listed(text).iter().position(|p| *p == "side.rs").unwrap();
        assert!(at(&focused) < at(&global), "{global}\n{focused}");
        assert_eq!(rt.store.file_rank_get(root).unwrap().unwrap(), stored);
        // Rebuilding from the same index writes the same bytes.
        refresh(&c, root).unwrap();
        assert_eq!(rt.store.file_rank_get(root).unwrap().unwrap(), stored);
        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn a_foreign_version_or_a_missing_graph_reads_as_absent() {
        let (rt, dir) = cx("rank-version");
        let c = Ctx::new(&rt);
        assert!(map(&c, "/none", 500, &[], NOW).is_none());
        rt.store.file_rank_put("/old", r#"{"v":0}"#).unwrap();
        assert!(map(&c, "/old", 500, &[], NOW).is_none());
        rt.store.file_rank_put("/junk", "not json").unwrap();
        assert!(map(&c, "/junk", 500, &[], NOW).is_none());
        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn the_map_fills_the_budget_in_one_pass_and_stays_under_it() {
        let (rt, dir) = cx("rank-budget");
        let root = "/rank/budget";
        seed_rows(&rt, root);
        let c = Ctx::new(&rt);
        refresh(&c, root).unwrap();
        let full = map(&c, root, 10_000, &[], NOW).unwrap();
        assert_eq!(listed(&full).len(), 5);
        let cap = c.estimate(&full, Class::Prose) - 1;
        let cut = map(&c, root, cap, &[], NOW).unwrap();
        assert!(listed(&cut).len() < 5, "{cut}");
        assert!(c.estimate(&cut, Class::Prose) <= cap, "{cut}");
        assert!(
            full.starts_with(&cut),
            "the cut map is a prefix of the full one"
        );
        assert!(map(&c, root, 1, &[], NOW).is_none(), "nothing fits");
        let _ = std::fs::remove_dir_all(dir);
    }

    /// The T370 backtest, run by hand: `cargo test --lib backtest -- --ignored --nocapture`.
    /// For each of the last 200 commits it asks how many of the commit's files (those the
    /// index at `HEAD` knows) a 1000-token map lists. The index is today's, not each commit's
    /// parent's, so the figure measures how well a map points at the files this tree's
    /// history keeps changing, not a replay of one session.
    #[test]
    #[ignore = "reads this repo's git history and indexes the whole tree"]
    fn backtest_recall_of_a_commits_files_in_a_1000_token_map() {
        const CAP: u32 = 1000;
        let root = std::path::Path::new(env!("CARGO_MANIFEST_DIR"));
        let (rt, dir) = cx("backtest");
        let c = Ctx::new(&rt);
        let started = std::time::Instant::now();
        super::super::index::run(&c, root, false).unwrap();
        let key = super::super::index::canon(root);
        let indexed = started.elapsed();
        let stored = rt.store.file_rank_get(&key).unwrap().expect("graph stored");
        let g: FileGraph = serde_json::from_str(&stored).unwrap();
        let log = std::process::Command::new("git")
            .current_dir(root)
            .args(["log", "-n", "200", "--name-only", "--format=format:@@"])
            .output()
            .unwrap();
        let log = String::from_utf8(log.stdout).unwrap();
        let commits: Vec<Vec<u32>> = log
            .split("@@")
            .skip(1)
            .map(|c| c.lines().filter_map(|f| g.index_of(f)).collect())
            .collect();
        let refs_files: std::collections::HashSet<String> = rt
            .store
            .symbol_top_refs(&key, i64::from(CAP))
            .unwrap()
            .into_iter()
            .scan(
                c.estimate("repo map\n", Class::Prose),
                |used, (name, refs, path, line)| {
                    *used += c.estimate(&format!("{name} {path}:{line} {refs}\n"), Class::Prose);
                    (*used <= CAP).then_some(path)
                },
            )
            .collect();
        let global: Vec<f64> = g.rank.iter().map(|r| f64::from(*r)).collect();
        let listed = |rank: &[f64]| -> std::collections::HashSet<u32> {
            let text = g.render(rank, CAP, &c).unwrap();
            text.lines()
                .skip(1)
                .filter_map(|l| g.index_of(l.split(": ").next()?))
                .collect()
        };
        let global_files = listed(&global);
        let mut rows: Vec<[f64; 3]> = Vec::new();
        for (i, files) in commits.iter().enumerate().filter(|(_, f)| !f.is_empty()) {
            let hit = |set: &dyn Fn(u32) -> bool| {
                files.iter().filter(|f| set(**f)).count() as f64 / files.len() as f64
            };
            // The working set a session would start from: what the three commits before this
            // one touched, standing in for the files edited in the last day.
            let mut recent: Vec<u32> = commits[i + 1..].iter().take(3).flatten().copied().collect();
            recent.sort_unstable();
            recent.dedup();
            let seeded_files = listed(&g.pagerank(&recent));
            rows.push([
                hit(&|f| refs_files.contains(&g.paths[f as usize])),
                hit(&|f| global_files.contains(&f)),
                hit(&|f| seeded_files.contains(&f)),
            ]);
        }
        let mean = |rows: &[[f64; 3]], col: usize| {
            100.0 * rows.iter().map(|r| r[col]).sum::<f64>() / rows.len() as f64
        };
        let summary = |label: &str, rows: &[[f64; 3]]| {
            println!(
                "recall@{CAP} tokens, {label} ({} commits): refs {:.1} %, pagerank {:.1} %, pagerank seeded {:.1} %",
                rows.len(),
                mean(rows, 0),
                mean(rows, 1),
                mean(rows, 2),
            );
        };
        println!(
            "backtest: index {indexed:?}, {} files, {} edges; map files refs {} pagerank {}",
            g.paths.len(),
            g.src.len(),
            refs_files.len(),
            global_files.len(),
        );
        let (newer, older) = rows.split_at(rows.len() / 2);
        summary("all", &rows);
        summary("newer half", newer);
        summary("older half", older);
        let _ = std::fs::remove_dir_all(dir);
    }
}
