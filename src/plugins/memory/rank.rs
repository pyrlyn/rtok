// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! Observation ranking for recall (T455). Formulas adapted from agentmemory v0.9.30
//! (`working-memory.ts` `scoreEntry`, `retention.ts` `computeRetention`, `hybrid-search.ts`
//! `diversifyBySession`) — Apache-2.0. Rank only; never DELETE (D4).

/// Working-memory score: importance×0.5 + recency×0.3 + access×0.2.
/// `importance` is 1..=10; `recency` = 1/(1+days×0.1); `access` = log2(count+1)/10.
pub fn score_entry(importance: i32, age_days: f64, access_count: i32) -> f64 {
    let importance_score = (importance.clamp(1, 10) as f64) / 10.0;
    let recency_score = 1.0 / (1.0 + age_days.max(0.0) * 0.1);
    let access_score = ((access_count.max(0) + 1) as f64).log2() / 10.0;
    importance_score * 0.5 + recency_score * 0.3 + access_score * 0.2
}

/// Retention used only as a rank multiplier (λ=0.01, σ=0.3).
/// `salience` defaults to importance/10; `access_ages_days` are days since each prior access.
pub fn retention(salience: f64, age_days: f64, access_ages_days: &[f64]) -> f64 {
    const LAMBDA: f64 = 0.01;
    const SIGMA: f64 = 0.3;
    let temporal = (-LAMBDA * age_days.max(0.0)).exp();
    let boost: f64 = access_ages_days
        .iter()
        .filter(|&&d| d > 0.0)
        .map(|d| 1.0 / d)
        .sum::<f64>()
        * SIGMA;
    (salience.max(0.0) * temporal + boost).min(1.0)
}

/// Combined rank score: working-memory × retention. Pinned rows skip this and sort first.
pub fn rank_score(
    importance: i32,
    age_days: f64,
    access_count: i32,
    access_ages_days: &[f64],
) -> f64 {
    let wm = score_entry(importance, age_days, access_count);
    let ret = retention(
        (importance.clamp(1, 10) as f64) / 10.0,
        age_days,
        access_ages_days,
    );
    wm * ret
}

/// Keep at most `max_per_session` from each session (in order), then backfill if under `limit`.
/// Returns indices into `sessions`.
pub fn diversify_indices(sessions: &[&str], limit: usize, max_per_session: usize) -> Vec<usize> {
    if limit == 0 || sessions.is_empty() {
        return Vec::new();
    }
    let max_per = max_per_session.max(1);
    let mut selected = Vec::new();
    let mut counts: std::collections::HashMap<&str, usize> = std::collections::HashMap::new();
    let mut taken = vec![false; sessions.len()];

    for (i, &s) in sessions.iter().enumerate() {
        let count = counts.get(s).copied().unwrap_or(0);
        if count >= max_per {
            continue;
        }
        selected.push(i);
        taken[i] = true;
        counts.insert(s, count + 1);
        if selected.len() >= limit {
            return selected;
        }
    }
    for (i, _) in sessions.iter().enumerate() {
        if selected.len() >= limit {
            break;
        }
        if taken[i] {
            continue;
        }
        selected.push(i);
        taken[i] = true;
    }
    selected
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn score_prefers_fresh_important_and_accessed() {
        let fresh = score_entry(5, 0.0, 0);
        let old = score_entry(5, 30.0, 0);
        assert!(fresh > old, "{fresh} vs {old}");
        let hot = score_entry(10, 0.0, 15);
        assert!(hot > fresh, "{hot} vs {fresh}");
    }

    #[test]
    fn retention_decays_with_age_and_rises_with_recent_access() {
        let cold = retention(0.5, 100.0, &[]);
        let warm = retention(0.5, 100.0, &[1.0]);
        assert!(warm > cold, "{warm} vs {cold}");
        assert!(retention(1.0, 0.0, &[]) <= 1.0);
    }

    #[test]
    fn diversify_caps_per_session_then_backfills() {
        let sessions = ["a", "a", "a", "a", "b", "c"];
        let got = diversify_indices(&sessions, 5, 3);
        assert_eq!(got.len(), 5);
        assert_eq!(got.iter().filter(|&&i| sessions[i] == "a").count(), 3);
        let thin = ["x", "y", "x", "y", "x"];
        assert_eq!(diversify_indices(&thin, 5, 1), vec![0, 1, 2, 3, 4]);
    }
}
