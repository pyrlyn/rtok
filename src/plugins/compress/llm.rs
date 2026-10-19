// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! T385.9.1: the P28 compressor client. One summarisation request per archived tool result,
//! sent to the proxy's own `internal` lane (so the spend is tagged and `[proxy.routing]`
//! applies), kept beside the archive row per compressor version, and stored only when it still
//! carries every must-keep span of the original. Anthropic Messages wire only. Nothing here
//! runs unless `[plugins.compress.llm] enabled`.

use std::path::PathBuf;
use std::sync::LazyLock;

use serde_json::{Value, json};

use super::must_keep::{self, Span};
use crate::config::{CompressLlm, Config};
use crate::store::Store;

/// The compressor prompt: data, not code (`prompt.toml`).
const PROMPT_FILE: &str = include_str!("prompt.toml");

struct Prompt {
    system: String,
    user: String,
}

static PROMPT: LazyLock<Prompt> = LazyLock::new(|| {
    let doc: toml::Table = PROMPT_FILE.parse().expect("prompt.toml parses");
    let field = |k: &str| {
        doc[k]
            .as_str()
            .expect("prompt.toml string")
            .trim()
            .to_string()
    };
    Prompt {
        system: field("system"),
        user: field("user"),
    }
});

const VERSION_HASH_CHARS: usize = 12;
/// The wire provider the compressor request is shaped for (`Wire::provider`).
const PROVIDER: &str = "anthropic";
const DEFAULT_ANTHROPIC_VERSION: &str = "2023-06-01";

/// Credentials of the agent turn that archived the result, forwarded to the compressor call so
/// it bills the same account. Held in memory for one job; never stored or logged.
#[derive(Clone, Default)]
pub struct Auth(Vec<(String, String)>);

impl Auth {
    /// `None` without a credential: the call would only fail.
    pub fn new(headers: Vec<(String, String)>) -> Option<Self> {
        let has = |n: &str| headers.iter().any(|(k, _)| k.eq_ignore_ascii_case(n));
        (has("x-api-key") || has("authorization")).then_some(Self(headers))
    }

    /// The credential and version headers of an agent request. Everything else (session ids,
    /// tracing, the lane header) stays behind so the compressor call is its own request.
    /// `None` unless the request came in on the Anthropic wire: the compressor speaks that wire
    /// only, so another provider's key would cross to the wrong destination.
    pub fn from_headers(
        headers: &reqwest::header::HeaderMap,
        provider: Option<&str>,
    ) -> Option<Self> {
        if provider != Some(PROVIDER) {
            return None;
        }
        let copied = Self::HEADERS
            .iter()
            .filter_map(|name| {
                let v = headers.get(*name)?.to_str().ok()?;
                Some(((*name).to_string(), v.to_string()))
            })
            .collect();
        Self::new(copied)
    }

    const HEADERS: [&str; 4] = [
        "x-api-key",
        "authorization",
        "anthropic-version",
        "anthropic-beta",
    ];
}

/// The cache key half beside the archive id: the prompt file's hash and the model, so editing
/// the prompt or changing the model invalidates every stored summary.
pub fn version(model: &str) -> String {
    let digest = crate::store::hex_sha256(PROMPT_FILE.as_bytes());
    format!("{}+{model}", &digest[..VERSION_HASH_CHARS])
}

/// What one job came to.
#[derive(Debug, PartialEq, Eq)]
pub enum Outcome {
    /// A summary for this archive id and version is already stored.
    Cached,
    /// Left to the extractive view, with the reason.
    Skipped(&'static str),
    /// The summary dropped these spans and was not stored.
    Rejected(Vec<String>),
    /// The upstream call or the store failed; nothing was stored.
    Failed(String),
    Stored,
}

pub struct Compressor {
    cfg: CompressLlm,
    full: Config,
    url: String,
    client: reqwest::Client,
    version: String,
    archive_dir: PathBuf,
}

impl Compressor {
    /// `None` while the flag is off, so a disabled build holds no client at all.
    pub fn new(cfg: &Config) -> anyhow::Result<Option<Self>> {
        let llm = cfg.plugins.compress.llm.clone();
        if !llm.enabled {
            return Ok(None);
        }
        let base = if llm.url.trim().is_empty() {
            let host = match cfg.proxy.bind.as_str() {
                "0.0.0.0" | "::" | "" => "127.0.0.1",
                bind => bind,
            };
            format!("http://{host}:{}", cfg.proxy.port)
        } else {
            llm.url.trim().trim_end_matches('/').to_string()
        };
        Ok(Some(Self {
            url: format!("{base}/v1/messages"),
            client: crate::tls::client(llm.timeout_s)?,
            version: version(&llm.model),
            archive_dir: cfg.core.archive_dir.clone(),
            full: cfg.clone(),
            cfg: llm,
        }))
    }

    /// The cache key half beside the archive id: prompt hash and model.
    pub fn version(&self) -> &str {
        &self.version
    }

    /// Summarise `archive_id` unless it is already summarised or not worth a call, check the
    /// result against the original's must-keep spans, and store it. Never panics and never
    /// returns an error: a failure is warned and reported as [`Outcome::Failed`].
    pub async fn run(&self, store: &Store, auth: &Auth, archive_id: &str) -> Outcome {
        let outcome = self.try_run(store, auth, archive_id).await;
        match &outcome {
            Outcome::Failed(e) => self.warn(store, &format!("{}: {e}", short(archive_id))),
            Outcome::Rejected(gone) => self.warn(
                store,
                &format!(
                    "{}: summary dropped {} must-keep span(s), not stored: {}",
                    short(archive_id),
                    gone.len(),
                    gone.join(" | ")
                ),
            ),
            _ => {}
        }
        outcome
    }

    async fn try_run(&self, store: &Store, auth: &Auth, archive_id: &str) -> Outcome {
        match store.archive_summary(archive_id, &self.version) {
            Ok(Some(_)) => return Outcome::Cached,
            Ok(None) => {}
            Err(e) => return Outcome::Failed(format!("cache read: {e}")),
        }
        let text = match store.get_archive(archive_id, Some(&self.archive_dir)) {
            Ok(Some(bytes)) => match String::from_utf8(bytes) {
                Ok(t) => t,
                Err(_) => return Outcome::Skipped("not text"),
            },
            Ok(None) => return Outcome::Skipped("archive payload missing"),
            Err(e) => return Outcome::Failed(format!("archive read: {e}")),
        };
        if text.len() as u64 > self.cfg.max_input_bytes {
            return Outcome::Skipped("too large");
        }
        if !self.cfg.code && is_code(&text) {
            return Outcome::Skipped("diff or source");
        }
        let spans = must_keep::spans(&text);
        if spans.len() > self.cfg.max_spans as usize {
            return Outcome::Skipped("too many must-keep spans");
        }
        let summary = match self.ask(auth, &text, &spans).await {
            Ok(s) => s,
            Err(e) => return Outcome::Failed(format!("{e:#}")),
        };
        // A summary the agent must not rely on is worse than the extractive view: the
        // original is under `expand`, but the agent would not know to look.
        if summary.is_empty() || summary.len() >= text.len() {
            return Outcome::Skipped("summary not shorter than the original");
        }
        let gone = must_keep::dropped(&spans, &summary);
        if !gone.is_empty() {
            return Outcome::Rejected(gone.iter().map(|s| s.text.clone()).collect());
        }
        match store.put_archive_summary(archive_id, &self.version, &summary) {
            Ok(_) => Outcome::Stored,
            Err(e) => Outcome::Failed(format!("cache write: {e}")),
        }
    }

    /// One request on the `internal` lane; the model's text, trimmed.
    async fn ask(&self, auth: &Auth, text: &str, spans: &[Span]) -> anyhow::Result<String> {
        let must_keep = spans
            .iter()
            .map(|s| format!("- {}", s.text))
            .collect::<Vec<_>>()
            .join("\n");
        // Split before filling: a tool result that contains `{{must_keep}}` stays text.
        let (head, tail) = PROMPT
            .user
            .split_once("{{text}}")
            .unwrap_or((&PROMPT.user, ""));
        let user = format!("{}{text}{tail}", head.replace("{{must_keep}}", &must_keep));
        let body = json!({
            "model": self.cfg.model,
            "max_tokens": self.cfg.max_output_tokens,
            "system": PROMPT.system,
            "messages": [{"role": "user", "content": user}],
        });
        let mut req = self
            .client
            .post(&self.url)
            .header("content-type", "application/json")
            .header(crate::lane::HEADER, "internal");
        if !auth
            .0
            .iter()
            .any(|(k, _)| k.eq_ignore_ascii_case("anthropic-version"))
        {
            req = req.header("anthropic-version", DEFAULT_ANTHROPIC_VERSION);
        }
        for (k, v) in &auth.0 {
            req = req.header(k.as_str(), v.as_str());
        }
        let resp = req.body(serde_json::to_vec(&body)?).send().await?;
        let status = resp.status();
        let bytes = resp.bytes().await?;
        anyhow::ensure!(status.is_success(), "upstream status {status}");
        let reply: Value = serde_json::from_slice(&bytes)?;
        anyhow::ensure!(
            reply["stop_reason"] != "max_tokens",
            "summary cut off at max_output_tokens"
        );
        let text: String = reply["content"]
            .as_array()
            .into_iter()
            .flatten()
            .filter(|b| b["type"] == "text")
            .filter_map(|b| b["text"].as_str())
            .collect();
        Ok(text.trim().to_string())
    }

    fn warn(&self, store: &Store, message: &str) {
        crate::log::record(
            &self.full, store, None, None, "warn", "plugin", "compress", message,
        );
    }
}

fn short(id: &str) -> &str {
    &id[..id.len().min(12)]
}

/// A diff or source file: logs and prose summarise well, these lose the exact lines the agent
/// edits against, so they wait for `code = true`.
fn is_code(text: &str) -> bool {
    let lines: Vec<&str> = text.lines().filter(|l| !l.trim().is_empty()).collect();
    if lines
        .iter()
        .any(|l| l.starts_with("diff --git ") || l.starts_with("@@ "))
    {
        return true;
    }
    let coded = lines
        .iter()
        .filter(|l| l.trim_end().ends_with(['{', '}', ';']))
        .count();
    coded * 100 >= lines.len() * 35
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn diffs_and_source_are_code_logs_are_not() {
        assert!(is_code(
            "diff --git a/x b/x\n--- a/x\n+++ b/x\n@@ -1 +1 @@\n-a\n+b"
        ));
        assert!(is_code("fn a() {\n    let x = 1;\n    x;\n}\n"));
        assert!(!is_code("running 3 tests\ntest a ... ok\nerror: boom\n"));
    }

    #[test]
    fn the_prompt_file_carries_both_placeholders_once() {
        assert_eq!(PROMPT.user.matches("{{must_keep}}").count(), 1);
        assert_eq!(PROMPT.user.matches("{{text}}").count(), 1);
        assert!(!PROMPT.system.is_empty());
    }

    #[test]
    fn only_anthropic_credentials_become_compressor_auth() {
        let mut headers = reqwest::header::HeaderMap::new();
        headers.insert("authorization", "Bearer sk-test".parse().unwrap());
        assert!(Auth::from_headers(&headers, Some("anthropic")).is_some());
        for provider in [Some("openai"), Some("gemini"), None] {
            assert!(
                Auth::from_headers(&headers, provider).is_none(),
                "{provider:?}"
            );
        }
    }

    #[test]
    fn auth_needs_a_credential() {
        assert!(Auth::new(vec![("anthropic-version".into(), "x".into())]).is_none());
        assert!(Auth::new(vec![("X-Api-Key".into(), "k".into())]).is_some());
    }
}
