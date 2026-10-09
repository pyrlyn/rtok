// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! Test helpers that used to live on the host (`testutil`). The store crate cannot call back.

use std::path::PathBuf;
use std::sync::atomic::{AtomicUsize, Ordering};

use super::Store;

pub fn tmp_dir(tag: &str) -> PathBuf {
    static NEXT: AtomicUsize = AtomicUsize::new(0);
    let n = NEXT.fetch_add(1, Ordering::Relaxed);
    let dir = std::env::temp_dir().join(format!("rtok-{tag}-{}-{n}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();
    dir
}

/// One proxy session, two api_request calls, usage for `anthropic` and `openai_chat`.
pub fn seed_two_apis(store: &Store) {
    store
        .upsert_session("s1", None, None, None, Some("proxy"))
        .unwrap();
    let id1 = store
        .insert_call(
            "s1",
            "proxy",
            "api_request",
            None,
            None,
            None,
            None,
            Some("/v1/messages"),
        )
        .unwrap();
    let id2 = store
        .insert_call(
            "s1",
            "proxy",
            "api_request",
            None,
            None,
            None,
            None,
            Some("/v1/chat/completions"),
        )
        .unwrap();
    store
        .insert_usage("s1", Some("m"), "anthropic", 10, 1, 2, 3, id1)
        .unwrap();
    store
        .insert_usage("s1", Some("m"), "openai_chat", 20, 0, 5, 4, id2)
        .unwrap();
}
