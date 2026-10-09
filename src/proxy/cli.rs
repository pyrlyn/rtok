// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! Lifecycle helpers for `rtok proxy` and `rtok agents install claude --proxy` (plan T5.2).

use std::sync::Arc;

use axum::Json;
use axum::extract::State;
use serde_json::{Value, json};

use super::ProxyState;

/// `GET /health` → `{"ok":true,"mode":…}`. When config disables proxy/core business
/// logic: `mode=passthrough`, `enabled=false`, `recording=false` (listener still up).
pub async fn health(State(state): State<Arc<ProxyState>>) -> Json<Value> {
    if state.plain() {
        Json(json!({
            "ok": true,
            "mode": "passthrough",
            "enabled": false,
            "recording": false,
            "live": super::live::snapshot().len(),
        }))
    } else {
        Json(json!({"ok": true, "mode": state.mode, "enabled": true, "recording": true}))
    }
}

/// `GET /live` → newest-first plain-proxy request summaries (in-memory only).
pub async fn live_calls() -> Json<Value> {
    Json(json!(super::live::snapshot()))
}
