// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! T668: the `[plugins.memory.embed] provider = "http"` embedder. One `POST <url>/embeddings`
//! per text on the OpenAI embeddings wire (OpenAI, Ollama, vLLM, LM Studio answer it). Opt-in
//! twice: `enabled = true` and `provider = "http"`; nothing here runs otherwise. Every failure
//! is logged and returns `None`, so the store serves the plain note search (fail open).

use std::io::Read;
use std::sync::OnceLock;
use std::time::Duration;

use anyhow::{Context, bail};
use reqwest::blocking::Client;
use rtok_store::TextEmbedder;
use serde_json::{Value, json};

use crate::config::MemoryEmbed;

/// A slow endpoint must not hold up `mem_save`/`mem_search` for long.
const TIMEOUT: Duration = Duration::from_secs(10);
/// The endpoint is untrusted input: a vector of a few thousand floats is far below this.
const MAX_BODY: u64 = 4 << 20;

pub struct HttpEmbedder {
    endpoint: String,
    model: String,
    key: Option<String>,
    dimensions: usize,
    max_chars: usize,
    client: OnceLock<Option<Client>>,
}

impl std::fmt::Debug for HttpEmbedder {
    // The key stays out of every log line and panic message.
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("HttpEmbedder")
            .field("endpoint", &self.endpoint)
            .field("model", &self.model)
            .finish_non_exhaustive()
    }
}

impl HttpEmbedder {
    /// `None` (with a warning) when the settings cannot work, so the caller keeps the hash
    /// embedder.
    pub fn from_config(cfg: &MemoryEmbed, getenv: impl Fn(&str) -> Option<String>) -> Option<Self> {
        match Self::try_new(cfg, getenv) {
            Ok(e) => Some(e),
            Err(e) => {
                log::warn!("memory embed: provider \"http\" unusable, using hash vectors: {e:#}");
                None
            }
        }
    }

    fn try_new(cfg: &MemoryEmbed, getenv: impl Fn(&str) -> Option<String>) -> anyhow::Result<Self> {
        let base = url::Url::parse(cfg.url.trim()).context("`url` is not a URL")?;
        if !matches!(base.scheme(), "http" | "https") {
            bail!("`url` must be http or https");
        }
        let key = if cfg.api_key_env.is_empty() {
            None
        } else {
            let key = getenv(&cfg.api_key_env)
                .filter(|k| !k.is_empty())
                .with_context(|| format!("${} is not set", cfg.api_key_env))?;
            let local = match base.host() {
                Some(url::Host::Domain(d)) => d == "localhost",
                Some(url::Host::Ipv4(ip)) => ip.is_loopback(),
                Some(url::Host::Ipv6(ip)) => ip.is_loopback(),
                None => false,
            };
            // The key goes with every request: never in clear text over a network.
            if base.scheme() == "http" && !local {
                bail!("`api_key_env` is set but `url` is plain http on a non-local host");
            }
            Some(key)
        };
        Ok(Self {
            endpoint: format!("{}/embeddings", base.as_str().trim_end_matches('/')),
            model: cfg.model.clone(),
            key,
            dimensions: cfg.dimensions as usize,
            max_chars: cfg.max_chars as usize,
            client: OnceLock::new(),
        })
    }

    fn request(&self, text: &str) -> anyhow::Result<Vec<f32>> {
        let client = self
            .client
            .get_or_init(|| {
                let tls = crate::tls::preconfigured().ok()?;
                Client::builder()
                    .use_preconfigured_tls(tls)
                    .user_agent(concat!("rtok/", env!("CARGO_PKG_VERSION")))
                    .timeout(TIMEOUT)
                    .build()
                    .ok()
            })
            .as_ref()
            .context("no HTTP client")?;
        let body = serde_json::to_vec(&json!({"model": self.model, "input": text}))?;
        let mut req = client
            .post(&self.endpoint)
            .header(reqwest::header::CONTENT_TYPE, "application/json")
            .body(body);
        if let Some(k) = &self.key {
            req = req.bearer_auth(k);
        }
        let res = req.send().context("request failed")?;
        if !res.status().is_success() {
            bail!("{} answered {}", self.endpoint, res.status());
        }
        let mut buf = Vec::new();
        res.take(MAX_BODY).read_to_end(&mut buf)?;
        let v: Value = serde_json::from_slice(&buf).context("answer is not JSON")?;
        let vector = v
            .pointer("/data/0/embedding")
            .and_then(Value::as_array)
            .context("answer has no data[0].embedding")?
            .iter()
            .map(|x| x.as_f64().map(|f| f as f32))
            .collect::<Option<Vec<f32>>>()
            .context("embedding holds a non-number")?;
        if vector.len() != self.dimensions {
            bail!(
                "model `{}` returned {} values, `dimensions` is {}",
                self.model,
                vector.len(),
                self.dimensions
            );
        }
        Ok(vector)
    }
}

impl TextEmbedder for HttpEmbedder {
    fn embed(&self, text: &str) -> Option<Vec<f32>> {
        if text.chars().count() > self.max_chars {
            log::warn!(
                "memory embed: text over `max_chars` ({}), not sent; using the note search",
                self.max_chars
            );
            return None;
        }
        match self.request(text) {
            Ok(v) => Some(v),
            Err(e) => {
                log::warn!("memory embed: {e:#}; using the note search");
                None
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use httpmock::prelude::*;
    use rtok_store::{EmbedSettings, Remote, Store};

    fn cfg(server: &MockServer) -> MemoryEmbed {
        MemoryEmbed {
            enabled: true,
            provider: "http".into(),
            model: "m1".into(),
            dimensions: 3,
            url: server.url("/v1"),
            ..MemoryEmbed::default()
        }
    }

    fn embedder(c: &MemoryEmbed) -> HttpEmbedder {
        HttpEmbedder::try_new(c, |_| None).unwrap()
    }

    fn answer(v: &[f32]) -> Value {
        json!({"object": "list", "data": [{"object": "embedding", "index": 0, "embedding": v}]})
    }

    #[test]
    fn posts_the_openai_wire_and_reads_the_vector() {
        let server = MockServer::start();
        let m = server.mock(|when, then| {
            when.method(POST)
                .path("/v1/embeddings")
                .header("authorization", "Bearer sk-test")
                .json_body(json!({"model": "m1", "input": "hello"}));
            then.status(200).json_body(answer(&[0.5, 0.25, 1.0]));
        });
        let mut c = cfg(&server);
        c.api_key_env = "K".into();
        let e = HttpEmbedder::try_new(&c, |k| (k == "K").then(|| "sk-test".to_string())).unwrap();
        assert_eq!(e.embed("hello"), Some(vec![0.5, 0.25, 1.0]));
        m.assert();
    }

    #[test]
    fn a_text_over_max_chars_is_never_sent() {
        let server = MockServer::start();
        let m = server.mock(|when, then| {
            when.any_request();
            then.status(200).json_body(answer(&[1.0, 0.0, 0.0]));
        });
        let mut c = cfg(&server);
        c.max_chars = 5;
        let e = embedder(&c);
        assert_eq!(e.embed("123456"), None);
        assert_eq!(e.embed("12345"), Some(vec![1.0, 0.0, 0.0]));
        m.assert_calls(1);
    }

    #[test]
    fn every_bad_answer_is_none() {
        let server = MockServer::start();
        let e = embedder(&cfg(&server));
        let cases = [
            (500, "{}".to_string()),
            (200, "not json".to_string()),
            (200, json!({"data": []}).to_string()),
            (
                200,
                json!({"data": [{"embedding": ["a", 1, 2]}]}).to_string(),
            ),
            (200, answer(&[1.0, 2.0]).to_string()),
        ];
        for (status, body) in cases {
            let mut m = server.mock(|when, then| {
                when.method(POST);
                then.status(status).body(&body);
            });
            assert_eq!(e.embed("x"), None, "{status} {body}");
            m.delete();
        }
    }

    #[test]
    fn an_unreachable_endpoint_is_none() {
        let c = MemoryEmbed {
            url: "http://127.0.0.1:1/v1".into(),
            dimensions: 3,
            ..MemoryEmbed::default()
        };
        assert_eq!(embedder(&c).embed("x"), None);
    }

    #[test]
    fn unusable_settings_keep_the_hash_embedder() {
        let mut c = MemoryEmbed::default();
        assert!(HttpEmbedder::try_new(&c, |_| None).is_err(), "no url");
        c.url = "ftp://localhost/v1".into();
        assert!(HttpEmbedder::try_new(&c, |_| None).is_err(), "not http(s)");
        c.url = "http://localhost:11434/v1".into();
        c.api_key_env = "K".into();
        assert!(HttpEmbedder::try_new(&c, |_| None).is_err(), "key unset");
        assert!(
            HttpEmbedder::try_new(&c, |_| Some("k".into())).is_ok(),
            "loopback http"
        );
        c.url = "http://embeddings.example.com/v1".into();
        assert!(
            HttpEmbedder::try_new(&c, |_| Some("k".into())).is_err(),
            "a key never travels in clear text"
        );
        c.api_key_env = String::new();
        assert!(
            HttpEmbedder::try_new(&c, |_| None).is_ok(),
            "no key, no secret to leak"
        );
    }

    #[test]
    fn debug_output_has_no_key() {
        let c = MemoryEmbed {
            url: "https://api.example.com/v1".into(),
            api_key_env: "K".into(),
            ..MemoryEmbed::default()
        };
        let e = HttpEmbedder::try_new(&c, |_| Some("sk-secret".into())).unwrap();
        assert!(!format!("{e:?}").contains("sk-secret"));
    }

    #[test]
    fn only_an_enabled_http_provider_builds_a_remote() {
        let server = MockServer::start();
        let on = cfg(&server);
        assert!(super::super::embed_settings(&on).remote.is_some());
        let off = MemoryEmbed {
            enabled: false,
            ..on.clone()
        };
        assert!(super::super::embed_settings(&off).remote.is_none());
        let local = MemoryEmbed {
            provider: "local".into(),
            ..on
        };
        assert!(super::super::embed_settings(&local).remote.is_none());
        assert!(
            super::super::embed_settings(&MemoryEmbed::default())
                .remote
                .is_none()
        );
    }

    /// The seam end to end: notes embedded and a synonym query ranked through the wire.
    #[test]
    fn semantic_search_through_the_http_embedder() {
        let server = MockServer::start();
        for (needle, v) in [
            ("car", [1.0, 0.0, 0.1]),
            ("banana", [0.0, 1.0, 0.1]),
            ("automobile", [1.0, 0.0, 0.1]),
        ] {
            server.mock(|when, then| {
                when.method(POST).body_includes(needle);
                then.status(200).json_body(answer(&v));
            });
        }
        let store = Store::open_in_memory().unwrap();
        for (title, body) in [
            ("fruit bowl", "a ripe banana"),
            ("garage", "the car is red"),
        ] {
            store.insert_note(None, "note", title, body).unwrap();
        }
        let c = cfg(&server);
        let settings = EmbedSettings {
            remote: Some(Remote(std::sync::Arc::new(embedder(&c)))),
            ..super::super::embed_settings(&MemoryEmbed {
                provider: "local".into(),
                ..c
            })
        };
        assert!(store.search_notes("automobile", 5).unwrap().is_empty());
        let hits = store
            .search_notes_hybrid("automobile", 1, &settings)
            .unwrap();
        assert_eq!(hits[0].title, "garage");
    }
}
