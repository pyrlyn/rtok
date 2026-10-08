// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! OpenAI Flex on the bulk and internal lanes (T385.5).
//!
//! Source: OpenAI "Flex processing", https://developers.openai.com/api/docs/guides/flex-processing
//! (checked 2026-10-08). `service_tier = "flex"` on Chat Completions and Responses is billed at
//! Batch rates. When Flex has no capacity it answers `429 Resource Unavailable` and does not
//! charge for it; the guide then offers two ways out: retry with exponential backoff, or retry
//! on standard processing with `service_tier` set to `auto` (or removed). The guide does not
//! name an error code, so the status alone identifies a capacity miss. A request that carries
//! Flex only because the client asked for it is the client's to retry: rtok retries a call only
//! when rtok set the tier itself.
//!
//! Anthropic has no Flex tier, so only the two OpenAI wires are touched.

use std::time::Duration;

use axum::body::Bytes;
use axum::http::StatusCode;
use serde_json::Value;

use crate::config::FlexPolicy;

/// The provider slug of the two OpenAI wires (`Wire::provider`).
const OPENAI: &str = "openai";
const TIER: &str = "service_tier";
const FLEX: &str = "flex";
/// Standard processing, which the guide names as the fallback tier.
const AUTO: &str = "auto";
/// Every retry holds the client's connection open, so the count has a ceiling whatever the
/// config says (`rtok config validate` rejects more).
const MAX_RETRIES: u32 = 5;
const MAX_DELAY: Duration = Duration::from_secs(30);

/// What to do with a Flex `429`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum OnBusy {
    None,
    Backoff,
    Default,
}

impl OnBusy {
    /// An unknown value is `none`: the 429 reaches the client, as if rtok were not there.
    fn parse(s: &str) -> Self {
        match s {
            "backoff" => OnBusy::Backoff,
            "default" => OnBusy::Default,
            _ => OnBusy::None,
        }
    }
}

/// Put `service_tier = "flex"` on `body`, or `None` to forward it as it is: not an OpenAI
/// request, no JSON object, or the client already chose a tier and `force` is off.
pub fn apply(provider: &str, body: &Bytes, flex: &FlexPolicy) -> Option<Bytes> {
    // Both OpenAI wires share this provider slug; the other wires carry their own.
    if provider != OPENAI {
        return None;
    }
    let parsed: Value = serde_json::from_slice(body).ok()?;
    match parsed.get(TIER) {
        // `null` is "unset" to the API, so it counts as omitted.
        None | Some(Value::Null) => {}
        Some(chosen) if flex.force && chosen != FLEX => {}
        Some(_) => return None,
    }
    set_tier(body, FLEX)
}

/// `body` with `service_tier` set to `tier`. Absent: the field is spliced in after the opening
/// brace, so every other byte stays as the client sent it. Present: only a re-serialisation
/// can replace the value.
fn set_tier(body: &[u8], tier: &str) -> Option<Bytes> {
    let mut parsed: Value = serde_json::from_slice(body).ok()?;
    let object = parsed.as_object_mut()?;
    if object.contains_key(TIER) {
        object.insert(TIER.to_string(), tier.into());
        return serde_json::to_vec(&parsed).ok().map(Bytes::from);
    }
    let open = body.iter().position(|b| !b.is_ascii_whitespace())?;
    let mut out = Vec::with_capacity(body.len() + 24);
    out.extend_from_slice(&body[..=open]);
    out.extend_from_slice(format!("\"{TIER}\":\"{tier}\"").as_bytes());
    if !object.is_empty() {
        out.push(b',');
    }
    out.extend_from_slice(&body[open + 1..]);
    Some(Bytes::from(out))
}

/// How one request that rtok sent as Flex is retried after a `429`.
#[derive(Debug, Clone)]
pub struct Retry {
    /// The body as it was before rtok set the tier.
    base: Bytes,
    on_busy: OnBusy,
    retries: u32,
    backoff: Duration,
}

impl Retry {
    /// `None` when the policy is `none`: nothing to retry.
    pub fn new(base: Bytes, flex: &FlexPolicy) -> Option<Self> {
        let on_busy = OnBusy::parse(&flex.on_429);
        (on_busy != OnBusy::None).then_some(Self {
            base,
            on_busy,
            retries: flex.retries.min(MAX_RETRIES),
            backoff: Duration::from_millis(flex.backoff_ms),
        })
    }

    /// The wait before retry `n` (0-based) and the body to send then (`None` = the same one),
    /// or `None` once the policy has no retry left.
    fn next(&self, n: u32) -> Option<(Duration, Option<Bytes>)> {
        match self.on_busy {
            OnBusy::Backoff if n < self.retries => {
                let wait = self.backoff.saturating_mul(1u32 << n).min(MAX_DELAY);
                Some((wait, None))
            }
            // One try on standard processing; a second 429 there is a real rate limit.
            OnBusy::Default if n == 0 => Some((Duration::ZERO, Some(set_tier(&self.base, AUTO)?))),
            _ => None,
        }
    }
}

/// The outcome of [`send`]: the last response, the body that produced it, and how many
/// retries it took.
pub struct Sent {
    pub result: Result<reqwest::Response, reqwest::Error>,
    pub body: Bytes,
    pub retries: u32,
}

/// Send `rb` with `body`; on a `429` retry as `retry` says. A `429` that is no longer
/// retried is returned to the caller like any other response.
pub async fn send(rb: reqwest::RequestBuilder, body: Bytes, retry: Option<&Retry>) -> Sent {
    let mut body = body;
    let mut retries = 0;
    loop {
        // A builder without a body always clones; without a clone there is no retry.
        let attempt = match rb.try_clone() {
            Some(b) => b.body(body.clone()),
            None => {
                return Sent {
                    result: rb.body(body.clone()).send().await,
                    body,
                    retries,
                };
            }
        };
        let result = attempt.send().await;
        let busy = result
            .as_ref()
            .is_ok_and(|r| r.status() == StatusCode::TOO_MANY_REQUESTS);
        let Some((wait, next)) = retry.filter(|_| busy).and_then(|r| r.next(retries)) else {
            return Sent {
                result,
                body,
                retries,
            };
        };
        // Dropping the 429 unread: Flex does not bill it, and no byte has gone to the client.
        drop(result);
        tokio::time::sleep(wait).await;
        if let Some(next) = next {
            body = next;
        }
        retries += 1;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn flex(force: bool, on_429: &str) -> FlexPolicy {
        FlexPolicy {
            force,
            on_429: on_429.to_string(),
            ..FlexPolicy::default()
        }
    }

    fn applied(body: &str, force: bool) -> Option<String> {
        apply(
            "openai",
            &Bytes::from(body.to_string()),
            &flex(force, "none"),
        )
        .map(|b| String::from_utf8(b.to_vec()).expect("utf8"))
    }

    #[test]
    fn an_omitted_tier_is_spliced_in_and_every_other_byte_stays() {
        let body = "{ \"model\" : \"m\",\n  \"messages\": [] }";
        let out = applied(body, false).expect("applied");
        assert_eq!(out, format!("{{\"service_tier\":\"flex\",{}", &body[1..]));
        let tiny = applied("{}", false).expect("applied");
        assert_eq!(tiny, r#"{"service_tier":"flex"}"#);
    }

    #[test]
    fn a_client_tier_is_respected_unless_forced() {
        for tier in ["auto", "default", "priority", "flex"] {
            let body = format!(r#"{{"model":"m","service_tier":"{tier}"}}"#);
            assert_eq!(applied(&body, false), None, "{tier} respected");
        }
        let body = r#"{"model":"m","service_tier":"priority"}"#;
        let forced: Value = serde_json::from_str(&applied(body, true).expect("forced")).unwrap();
        assert_eq!(forced["service_tier"], "flex");
        assert_eq!(forced["model"], "m");
        assert_eq!(applied(r#"{"service_tier":"flex"}"#, true), None);
    }

    #[test]
    fn a_null_tier_counts_as_omitted() {
        let out = applied(r#"{"model":"m","service_tier":null}"#, false).expect("applied");
        let v: Value = serde_json::from_str(&out).unwrap();
        assert_eq!(v["service_tier"], "flex");
    }

    #[test]
    fn other_wires_and_bodies_are_left_alone() {
        let body = Bytes::from_static(b"{\"model\":\"m\"}");
        for provider in ["anthropic", "gemini"] {
            assert!(apply(provider, &body, &flex(true, "none")).is_none());
        }
        for bad in ["", "not json", "[1]", "\"x\""] {
            assert!(applied(bad, true).is_none(), "{bad:?}");
        }
    }

    #[test]
    fn retry_schedule_follows_the_policy() {
        let base = Bytes::from_static(br#"{"model":"m"}"#);
        assert!(Retry::new(base.clone(), &flex(false, "none")).is_none());
        assert!(Retry::new(base.clone(), &flex(false, "bogus")).is_none());

        let mut p = flex(false, "backoff");
        p.retries = 99;
        p.backoff_ms = 10_000;
        let r = Retry::new(base.clone(), &p).expect("backoff");
        let waits: Vec<_> = (0..7)
            .map(|n| r.next(n).map(|(w, b)| (w.as_secs(), b)))
            .collect();
        assert_eq!(
            waits,
            [
                Some((10, None)),
                Some((20, None)),
                Some((30, None)),
                Some((30, None)),
                Some((30, None)),
                None,
                None
            ]
        );

        let d = Retry::new(base, &flex(false, "default")).expect("default");
        let (wait, body) = d.next(0).expect("one retry");
        assert_eq!(wait, Duration::ZERO);
        assert_eq!(
            body.expect("auto body"),
            r#"{"service_tier":"auto","model":"m"}"#
        );
        assert!(d.next(1).is_none());
    }
}
