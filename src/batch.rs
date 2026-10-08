// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! `rtok batch submit|status|fetch` (T385.12.1): a thin client for the providers' own Batch
//! APIs that sends every call through `rtok proxy`, so the proxy's `batch` lane sees them.
//!
//! It never converts a sync request into a Batch job and never rewrites a JSONL line: the
//! Anthropic body is the caller's lines joined into `requests`, the OpenAI file is uploaded as
//! it is. Wire shapes: https://platform.claude.com/docs/en/build-with-claude/batch-processing
//! and https://developers.openai.com/api/docs/guides/batch (both checked 2026-10-08).

use std::io::Write;
use std::path::Path;

use anyhow::{Context, Result, bail};
use reqwest::{Client, RequestBuilder, Response, header};
use serde_json::{Value, json};

#[derive(Debug, Clone, Copy, PartialEq, Eq, clap::ValueEnum)]
pub enum Provider {
    Anthropic,
    Openai,
}

impl Provider {
    /// The environment variable holding this provider's API key.
    pub fn key_env(self) -> &'static str {
        match self {
            Provider::Anthropic => "ANTHROPIC_API_KEY",
            Provider::Openai => "OPENAI_API_KEY",
        }
    }

    /// The provider that issued `id`: Message Batch ids start with `msgbatch_`, OpenAI's with
    /// `batch_`.
    pub fn of_id(id: &str) -> Provider {
        if id.starts_with("msgbatch_") {
            Provider::Anthropic
        } else {
            Provider::Openai
        }
    }
}

/// One provider reached through one proxy base URL.
pub struct Api {
    client: Client,
    base: String,
    provider: Provider,
    key: String,
}

const MULTIPART_BOUNDARY: &str = "rtok-batch-7f3a9c1e";
/// How much of an error body is echoed; a provider error is short, a proxy HTML page is not.
const ERROR_SNIPPET: usize = 300;

impl Api {
    pub fn new(base: &str, provider: Provider, key: &str) -> Result<Self> {
        if !(base.starts_with("http://") || base.starts_with("https://")) {
            bail!("proxy url must start with http:// or https://: {base}");
        }
        if key.trim().is_empty() {
            bail!("{} is not set", provider.key_env());
        }
        Ok(Self {
            client: Client::new(),
            base: base.trim_end_matches('/').to_string(),
            provider,
            key: key.trim().to_string(),
        })
    }

    fn request(&self, post: bool, path: &str) -> RequestBuilder {
        let url = format!("{}{path}", self.base);
        let rb = if post {
            self.client.post(url)
        } else {
            self.client.get(url)
        };
        match self.provider {
            Provider::Anthropic => rb
                .header("x-api-key", &self.key)
                .header("anthropic-version", "2023-06-01"),
            Provider::Openai => rb.bearer_auth(&self.key),
        }
    }

    async fn send(&self, rb: RequestBuilder) -> Result<Response> {
        let resp = rb
            .send()
            .await
            .context("cannot reach the proxy (is `rtok proxy` running?)")?;
        if resp.status().is_success() {
            return Ok(resp);
        }
        let status = resp.status();
        let body = resp.text().await.unwrap_or_default();
        let snippet: String = body.chars().take(ERROR_SNIPPET).collect();
        bail!("{status}: {snippet}")
    }

    async fn text(&self, rb: RequestBuilder) -> Result<String> {
        Ok(self.send(rb).await?.text().await?)
    }

    fn json_post(&self, path: &str, body: String) -> RequestBuilder {
        self.request(true, path)
            .header(header::CONTENT_TYPE, "application/json")
            .body(body)
    }

    /// Create a batch from the JSONL at `file`; returns the provider's batch object.
    pub async fn submit(&self, file: &Path) -> Result<String> {
        let raw =
            std::fs::read_to_string(file).with_context(|| format!("read {}", file.display()))?;
        let (lines, url) = validate(&raw, self.provider)?;
        match self.provider {
            Provider::Anthropic => {
                let body = format!("{{\"requests\":[{}]}}", lines.join(","));
                self.text(self.json_post("/v1/messages/batches", body))
                    .await
            }
            Provider::Openai => {
                if raw.contains(MULTIPART_BOUNDARY) {
                    bail!(
                        "{} contains the multipart boundary {MULTIPART_BOUNDARY}",
                        file.display()
                    );
                }
                let b = MULTIPART_BOUNDARY;
                let form = format!(
                    "--{b}\r\nContent-Disposition: form-data; name=\"purpose\"\r\n\r\nbatch\r\n\
                     --{b}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"batch.jsonl\"\r\n\
                     Content-Type: application/jsonl\r\n\r\n{raw}\r\n--{b}--\r\n"
                );
                let upload = self
                    .request(true, "/v1/files")
                    .header(
                        header::CONTENT_TYPE,
                        format!("multipart/form-data; boundary={b}"),
                    )
                    .body(form);
                let uploaded: Value = serde_json::from_str(&self.text(upload).await?)?;
                let Some(file_id) = uploaded["id"].as_str() else {
                    bail!("the upload answered without a file id");
                };
                let create = json!({
                    "input_file_id": file_id,
                    "endpoint": url,
                    "completion_window": "24h",
                });
                self.text(self.json_post("/v1/batches", create.to_string()))
                    .await
            }
        }
    }

    pub async fn status(&self, id: &str) -> Result<String> {
        check_id(id)?;
        self.text(self.request(false, &self.batch_path(id))).await
    }

    /// Write the results of a finished batch to `out`, which must not exist yet.
    pub async fn fetch(&self, id: &str, out: &Path) -> Result<u64> {
        check_id(id)?;
        // Before the first request: a refused target should not cost a download.
        if out.exists() {
            bail!("{} exists; results are never overwritten", out.display());
        }
        let path = match self.provider {
            Provider::Anthropic => format!("{}/results", self.batch_path(id)),
            Provider::Openai => {
                let batch: Value = serde_json::from_str(&self.status(id).await?)?;
                let Some(file) = batch["output_file_id"].as_str() else {
                    let state = batch["status"].as_str().unwrap_or("unknown");
                    bail!("batch {id} has no output file yet (status: {state})");
                };
                check_id(file)?;
                format!("/v1/files/{file}/content")
            }
        };
        let mut resp = self.send(self.request(false, &path)).await?;
        let mut sink = std::fs::OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(out)
            .with_context(|| format!("create {} (it must not exist)", out.display()))?;
        let mut written = 0u64;
        let copied: Result<()> = async {
            while let Some(chunk) = resp.chunk().await? {
                sink.write_all(&chunk)?;
                written += chunk.len() as u64;
            }
            Ok(())
        }
        .await;
        if let Err(e) = copied {
            // The file is ours (create_new), so a torn download is not left behind.
            let _ = std::fs::remove_file(out);
            return Err(e);
        }
        Ok(written)
    }

    fn batch_path(&self, id: &str) -> String {
        match self.provider {
            Provider::Anthropic => format!("/v1/messages/batches/{id}"),
            Provider::Openai => format!("/v1/batches/{id}"),
        }
    }
}

/// An id goes into a URL path: keep it to the characters providers issue.
fn check_id(id: &str) -> Result<()> {
    if id.is_empty()
        || !id
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-')
    {
        bail!("not a batch or file id: {id:?}");
    }
    Ok(())
}

/// The non-empty lines of `raw` once each is checked to be a request of `provider`'s shape,
/// plus the endpoint every OpenAI line targets (empty for Anthropic).
fn validate(raw: &str, provider: Provider) -> Result<(Vec<&str>, String)> {
    let required: &[&str] = match provider {
        Provider::Anthropic => &["custom_id", "params"],
        Provider::Openai => &["custom_id", "method", "url", "body"],
    };
    let mut lines = Vec::new();
    let mut url = String::new();
    for (n, line) in raw
        .lines()
        .enumerate()
        .filter(|(_, l)| !l.trim().is_empty())
    {
        let v: Value =
            serde_json::from_str(line).with_context(|| format!("line {}: not JSON", n + 1))?;
        if let Some(missing) = required.iter().find(|k| v.get(**k).is_none()) {
            bail!("line {}: missing `{missing}`", n + 1);
        }
        if provider == Provider::Openai {
            let this = v["url"].as_str().unwrap_or_default();
            if url.is_empty() {
                url = this.to_string();
            } else if url != this {
                bail!(
                    "line {}: url `{this}` differs from `{url}`; a batch targets one endpoint",
                    n + 1
                );
            }
        }
        lines.push(line.trim());
    }
    if lines.is_empty() {
        bail!("no requests in the file");
    }
    Ok((lines, url))
}

#[cfg(test)]
mod tests {
    use super::*;

    const ANTHROPIC: &str =
        r#"{"custom_id":"a","params":{"model":"m","max_tokens":1,"messages":[]}}"#;
    const OPENAI: &str =
        r#"{"custom_id":"a","method":"POST","url":"/v1/chat/completions","body":{}}"#;

    #[test]
    fn validation_names_the_line_and_the_missing_key() {
        let two = format!("{ANTHROPIC}\n\n{ANTHROPIC}\n");
        assert_eq!(validate(&two, Provider::Anthropic).unwrap().0.len(), 2);
        let err = validate(
            &format!("{ANTHROPIC}\n{{\"custom_id\":\"b\"}}"),
            Provider::Anthropic,
        )
        .unwrap_err();
        assert_eq!(err.to_string(), "line 2: missing `params`");
        assert!(
            validate("nope", Provider::Anthropic)
                .unwrap_err()
                .to_string()
                .contains("line 1")
        );
        assert!(validate("\n \n", Provider::Openai).is_err());
    }

    #[test]
    fn an_openai_batch_targets_one_endpoint() {
        let (_, url) = validate(OPENAI, Provider::Openai).unwrap();
        assert_eq!(url, "/v1/chat/completions");
        let other = OPENAI.replace("chat/completions", "responses");
        assert!(validate(&format!("{OPENAI}\n{other}"), Provider::Openai).is_err());
    }

    #[test]
    fn ids_cannot_escape_the_url_path() {
        assert!(check_id("msgbatch_01-AB").is_ok());
        for bad in ["", "a/b", "../x", "a?b", "a b"] {
            assert!(check_id(bad).is_err(), "{bad}");
        }
        assert_eq!(Provider::of_id("msgbatch_1"), Provider::Anthropic);
        assert_eq!(Provider::of_id("batch_1"), Provider::Openai);
    }

    #[test]
    fn a_missing_key_or_bad_url_is_refused_before_any_request() {
        assert!(Api::new("http://127.0.0.1:1", Provider::Openai, " ").is_err());
        assert!(Api::new("ftp://x", Provider::Openai, "k").is_err());
    }
}
