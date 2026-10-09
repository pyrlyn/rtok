// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

/// `[estimator]` — chars per token per class, rewritten by `stats --calibrate`.
#[derive(Debug, Clone, PartialEq, serde::Serialize, serde::Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct Estimator {
    pub code: f32,
    pub prose: f32,
    pub json: f32,
    pub cjk: f32,
}

impl Default for Estimator {
    fn default() -> Self {
        Self {
            code: 3.5,
            prose: 4.2,
            json: 3.0,
            cjk: 1.0,
        }
    }
}
