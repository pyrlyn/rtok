// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! `calls.kind` strings for proxied requests (T385).
//!
//! The proxy classifies a request into a lane and records that lane's kind.
//! The store, `measure` and `otel` read the same strings back. The strings
//! live here so those readers do not depend on the proxy.

/// Agent lane. Existing rows and reports already use the bare name.
pub const KIND_AGENT: &str = "api_request";
pub const KIND_BULK: &str = "api_request:bulk";
pub const KIND_BATCH: &str = rtok_store::BATCH_CALL_KIND;
pub const KIND_FILES: &str = "api_request:files";
pub const KIND_EMBEDDINGS: &str = "api_request:embeddings";
pub const KIND_META: &str = "api_request:meta";
pub const KIND_INTERNAL: &str = "api_request:internal";

/// True for a proxied-request `calls.kind`, whichever lane tagged it.
pub fn is_api_request(kind: &str) -> bool {
    kind == KIND_AGENT || kind.starts_with("api_request:")
}

/// The lane name a `calls.kind` was recorded under. The bare `api_request` is
/// the agent lane, which is how rows written before lanes existed read back.
pub fn lane_of_kind(kind: &str) -> &str {
    kind.strip_prefix("api_request:").unwrap_or("agent")
}
