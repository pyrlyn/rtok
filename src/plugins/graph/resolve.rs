// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! T368: import evidence, then directory. Above ~5% of files, or a same-class tie, stays unresolved — one name has one IDF.

use std::collections::{HashMap, HashSet};

use anyhow::Result;
use rtok_plugin_sdk::Ctx;

#[derive(Clone, Debug)]
pub(crate) struct Hit {
    /// `None` keeps every reference: unambiguous, too common, or nothing resolved.
    pub files: Option<HashSet<String>>,
    pub others: usize,
    pub def_path: Option<String>,
}

impl Hit {
    fn unresolved() -> Self {
        Self {
            files: None,
            others: 0,
            def_path: None,
        }
    }

    pub(crate) fn allows(&self, path: &str) -> bool {
        self.files.as_ref().is_none_or(|files| files.contains(path))
    }
}

/// More than ~5% of indexed files reference `name`.
fn too_common(df: i64, n_files: i64) -> bool {
    n_files > 0 && df.saturating_mul(20) > n_files
}

fn idf(df: i64, n_files: i64) -> f64 {
    if df <= 0 || n_files <= 0 {
        return 0.0;
    }
    (n_files as f64 / df as f64).ln()
}

fn parent_dir(path: &str) -> &str {
    path.rsplit_once('/').map(|(dir, _)| dir).unwrap_or("")
}

fn same_dir(a: &str, b: &str) -> bool {
    parent_dir(a) == parent_dir(b)
}

struct Cand {
    path: String,
    class: u8,
    idf: f64,
    files: HashSet<String>,
}

fn beats(a: &Cand, b: &Cand) -> bool {
    a.class < b.class || (a.class == b.class && a.idf > b.idf)
}

pub(crate) fn rank_name(cx: &Ctx, root: &str, name: &str) -> Result<Hit> {
    let defs = cx.symbol_defs(root, name)?;
    if defs.len() < 2 {
        return Ok(Hit::unresolved());
    }
    let (df, n_files) = cx.symbol_name_freq(root, name)?;
    if too_common(df, n_files) {
        return Ok(Hit::unresolved());
    }
    let name_idf = idf(df, n_files);
    let mut imported: HashMap<String, HashSet<String>> = HashMap::new();
    for (ref_file, def_file) in cx.symbol_imported_defs(root, name)? {
        imported.entry(ref_file).or_default().insert(def_file);
    }
    let mut by: HashMap<String, Cand> = HashMap::new();
    for (path, ..) in cx.symbol_ref_groups(root, name)? {
        let mut best: Vec<&str> = Vec::new();
        let mut class = 3u8;
        for (def, ..) in &defs {
            let c = if imported.get(&path).is_some_and(|s| s.contains(def)) {
                0
            } else if same_dir(&path, def) {
                1
            } else {
                2
            };
            if c < class {
                class = c;
                best.clear();
                best.push(def.as_str());
            } else if c == class {
                best.push(def.as_str());
            }
        }
        // Class 2 is "no evidence". A tie at the best class is not a resolution.
        if class >= 2 || best.len() != 1 {
            continue;
        }
        let winner = best[0];
        let cand = by.entry(winner.to_string()).or_insert_with(|| Cand {
            path: winner.to_string(),
            class: 3,
            idf: name_idf,
            files: HashSet::new(),
        });
        if class < cand.class {
            cand.class = class;
        }
        cand.files.insert(path);
    }
    let mut top: Option<&Cand> = None;
    let mut tied = false;
    for cand in by.values() {
        match top {
            None => top = Some(cand),
            Some(best) if beats(cand, best) => {
                top = Some(cand);
                tied = false;
            }
            Some(best) if !beats(best, cand) => tied = true,
            Some(_) => {}
        }
    }
    let Some(winner) = top.filter(|_| !tied) else {
        return Ok(Hit::unresolved());
    };
    Ok(Hit {
        files: Some(winner.files.clone()),
        others: defs.len().saturating_sub(1),
        def_path: Some(winner.path.clone()),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn five_percent_is_the_common_name_cutoff() {
        assert!(!too_common(5, 100));
        assert!(too_common(6, 100));
        assert!(!too_common(0, 10));
        assert!(idf(1, 100) > idf(50, 100));
    }
}
