// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! Request lanes (T385.1): which kind of traffic a proxied request is, so the ledger can tell
//! agent turns from bulk scripts, Batch jobs, uploads and rtok's own calls.
//!
//! The classifier is deliberately dumb. A path that names its own kind of traffic (Batch,
//! files, embeddings, models/token counting) decides the lane; an explicit `x-rtok-lane`
//! header or `/lane/<name>/` path prefix picks among what a path cannot say (a sync chat
//! call is an agent turn or a bulk script — only the caller knows). No heuristics.

use axum::http::HeaderMap;

/// Request header naming the lane; stripped before the request goes upstream.
pub const HEADER: &str = "x-rtok-lane";
const PREFIX: &str = "/lane/";

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Lane {
    Agent,
    Bulk,
    Batch,
    Files,
    Embeddings,
    Meta,
    Internal,
}

impl Lane {
    const ALL: [Lane; 7] = [
        Lane::Agent,
        Lane::Bulk,
        Lane::Batch,
        Lane::Files,
        Lane::Embeddings,
        Lane::Meta,
        Lane::Internal,
    ];

    pub fn name(self) -> &'static str {
        match self {
            Lane::Agent => "agent",
            Lane::Bulk => "bulk",
            Lane::Batch => "batch",
            Lane::Files => "files",
            Lane::Embeddings => "embeddings",
            Lane::Meta => "meta",
            Lane::Internal => "internal",
        }
    }

    fn from_name(name: &str) -> Option<Lane> {
        let name = name.trim();
        Lane::ALL
            .into_iter()
            .find(|lane| lane.name().eq_ignore_ascii_case(name))
    }

    /// The `calls.kind` this lane is recorded under. The agent lane keeps the bare
    /// `api_request` every existing row and report already uses, so default traffic leaves
    /// the ledger exactly as before; the rest are `api_request:<lane>`, which `is_api_request`
    /// still recognises.
    pub fn kind(self) -> &'static str {
        match self {
            Lane::Agent => "api_request",
            Lane::Bulk => "api_request:bulk",
            Lane::Batch => "api_request:batch",
            Lane::Files => "api_request:files",
            Lane::Embeddings => "api_request:embeddings",
            Lane::Meta => "api_request:meta",
            Lane::Internal => "api_request:internal",
        }
    }
}

/// True for a proxied-request `calls.kind`, whichever lane tagged it.
pub fn is_api_request(kind: &str) -> bool {
    kind == "api_request" || kind.starts_with("api_request:")
}

/// The lane of one request and the path to forward (the `/lane/<name>` prefix removed).
#[derive(Debug, PartialEq, Eq)]
pub struct Classified<'a> {
    pub lane: Lane,
    pub path: &'a str,
}

/// Classify `path` + `headers`. Unknown paths and sync chat wires fall to `agent` — what the
/// proxy treated them as before lanes existed.
pub fn classify<'a>(path: &'a str, headers: &HeaderMap) -> Classified<'a> {
    let (prefixed, path) = strip_prefix(path);
    let marked = prefixed.or_else(|| {
        headers
            .get(HEADER)
            .and_then(|v| v.to_str().ok())
            .and_then(Lane::from_name)
    });
    Classified {
        lane: by_path(path).or(marked).unwrap_or(Lane::Agent),
        path,
    }
}

/// `/lane/<name>/rest` → (`<name>`, `/rest`). An unknown name is not ours to strip.
fn strip_prefix(path: &str) -> (Option<Lane>, &str) {
    let Some(rest) = path.strip_prefix(PREFIX) else {
        return (None, path);
    };
    match rest.split_once('/') {
        Some((name, tail)) => match Lane::from_name(name) {
            // `tail` starts after the slash; the forwarded path keeps it.
            Some(lane) => (Some(lane), &path[path.len() - tail.len() - 1..]),
            None => (None, path),
        },
        None => (None, path),
    }
}

fn under(path: &str, base: &str) -> bool {
    path.strip_prefix(base)
        .is_some_and(|rest| rest.is_empty() || rest.starts_with('/'))
}

/// The lane a path names by itself, across the Anthropic, OpenAI and Gemini surfaces.
fn by_path(path: &str) -> Option<Lane> {
    // Gemini puts the verb after a colon on the model path.
    if (under(path, "/v1beta/models") || under(path, "/v1/models"))
        && let Some((_, action)) = path.rsplit_once(':')
    {
        return match action {
            "batchGenerateContent" | "asyncBatchEmbedContent" => Some(Lane::Batch),
            "embedContent" | "batchEmbedContents" => Some(Lane::Embeddings),
            "countTokens" => Some(Lane::Meta),
            _ => None,
        };
    }
    const BATCH: [&str; 3] = ["/v1/messages/batches", "/v1/batches", "/v1beta/batches"];
    const FILES: [&str; 3] = ["/v1/files", "/v1beta/files", "/upload/v1beta/files"];
    const META: [&str; 3] = ["/v1/messages/count_tokens", "/v1/models", "/v1beta/models"];
    let any = |bases: &[&str]| bases.iter().any(|base| under(path, base));
    if any(&BATCH) {
        Some(Lane::Batch)
    } else if any(&FILES) {
        Some(Lane::Files)
    } else if under(path, "/v1/embeddings") {
        Some(Lane::Embeddings)
    } else if any(&META) {
        Some(Lane::Meta)
    } else {
        None
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::http::HeaderValue;
    use rstest::rstest;

    fn lane_of(path: &str, header: Option<&str>) -> (Lane, String) {
        let mut headers = HeaderMap::new();
        if let Some(v) = header {
            headers.insert(HEADER, HeaderValue::from_str(v).unwrap());
        }
        let c = classify(path, &headers);
        (c.lane, c.path.to_string())
    }

    #[rstest]
    #[case("/v1/messages", Lane::Agent)]
    #[case("/v1/chat/completions", Lane::Agent)]
    #[case("/v1beta/models/gemini-2.0-flash:generateContent", Lane::Agent)]
    #[case("/v1/something/new", Lane::Agent)]
    #[case("/v1/messages/batches", Lane::Batch)]
    #[case("/v1/messages/batches/msgbatch_1/results", Lane::Batch)]
    #[case("/v1/batches/batch_1/cancel", Lane::Batch)]
    #[case("/v1beta/models/gemini-2.0-flash:batchGenerateContent", Lane::Batch)]
    #[case("/v1/files", Lane::Files)]
    #[case("/v1/files/file_1/content", Lane::Files)]
    #[case("/upload/v1beta/files", Lane::Files)]
    #[case("/v1/embeddings", Lane::Embeddings)]
    #[case("/v1beta/models/text-embedding-004:embedContent", Lane::Embeddings)]
    #[case("/v1/models", Lane::Meta)]
    #[case("/v1/models/gpt-5", Lane::Meta)]
    #[case("/v1/messages/count_tokens", Lane::Meta)]
    #[case("/v1beta/models/gemini-2.0-flash:countTokens", Lane::Meta)]
    fn paths_name_their_lane(#[case] path: &str, #[case] lane: Lane) {
        assert_eq!(lane_of(path, None), (lane, path.to_string()));
    }

    #[test]
    fn marker_picks_among_what_a_path_cannot_say() {
        assert_eq!(lane_of("/v1/messages", Some("Bulk")).0, Lane::Bulk);
        assert_eq!(lane_of("/v1/responses", Some("internal")).0, Lane::Internal);
        // A structural path keeps its lane whatever the caller claims.
        assert_eq!(lane_of("/v1/messages/batches", Some("bulk")).0, Lane::Batch);
        // Not a lane: ignored, never an error.
        assert_eq!(lane_of("/v1/messages", Some("turbo")).0, Lane::Agent);
    }

    #[test]
    fn prefix_is_stripped_and_beats_the_header() {
        assert_eq!(
            lane_of("/lane/bulk/v1/messages", Some("internal")),
            (Lane::Bulk, "/v1/messages".to_string())
        );
        assert_eq!(
            lane_of("/lane/bulk/v1/files/f1", None),
            (Lane::Files, "/v1/files/f1".to_string())
        );
    }

    #[rstest]
    #[case("/lane/turbo/v1/messages")]
    #[case("/lane/bulk")]
    #[case("/lane/")]
    #[case("/lanes/bulk/v1/messages")]
    fn foreign_prefixes_are_forwarded_untouched(#[case] path: &str) {
        assert_eq!(lane_of(path, None), (Lane::Agent, path.to_string()));
    }

    #[test]
    fn kinds_stay_api_requests() {
        for lane in Lane::ALL {
            assert!(is_api_request(lane.kind()), "{}", lane.name());
        }
        assert!(!is_api_request("hook"));
        assert!(!is_api_request("api_requests"));
    }
}
