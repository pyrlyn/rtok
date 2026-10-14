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

/// `GET /live` → newest-first plain-proxy request summaries (in-memory only). Operator data:
/// only a loopback peer gets it, so a proxy bound to `0.0.0.0` does not publish its traffic.
pub async fn live_calls(req: axum::extract::Request) -> axum::response::Response {
    use axum::response::IntoResponse;
    let peer = req
        .extensions()
        .get::<axum::extract::ConnectInfo<std::net::SocketAddr>>()
        .map(|c| c.0);
    if !peer_is_local(peer) {
        return axum::http::StatusCode::FORBIDDEN.into_response();
    }
    Json(json!(super::live::snapshot())).into_response()
}

/// A loopback peer, or none known (an in-process router without connect info).
pub(crate) fn peer_is_local(peer: Option<std::net::SocketAddr>) -> bool {
    peer.is_none_or(|p| p.ip().is_loopback() || p.ip().to_canonical().is_loopback())
}

#[cfg(test)]
mod peer_tests {
    use super::peer_is_local;

    #[test]
    fn only_loopback_peers_read_live_calls() {
        assert!(peer_is_local(None));
        assert!(peer_is_local(Some("127.0.0.1:5000".parse().unwrap())));
        assert!(peer_is_local(Some("[::1]:5000".parse().unwrap())));
        assert!(peer_is_local(Some(
            "[::ffff:127.0.0.1]:5000".parse().unwrap()
        )));
        assert!(!peer_is_local(Some("192.168.1.9:5000".parse().unwrap())));
        assert!(!peer_is_local(Some("[2001:db8::1]:5000".parse().unwrap())));
    }
}
