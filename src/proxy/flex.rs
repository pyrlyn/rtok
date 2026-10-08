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
//! The guide also says Flex requests time out more often and shows a 15 minute timeout in every
//! SDK sample (checked 2026-10-08), so a Flex lane reads for at least [`MIN_TIMEOUT_S`]. It names
//! `408` as the SDKs' own retry and says nothing about `Retry-After`: a `408` is therefore never
//! retried here, and `Retry-After` on a `429` is read as the standard HTTP header.
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
/// The 15 minutes the guide's SDK samples raise the timeout to (the SDK default is 10).
const MIN_TIMEOUT_S: u64 = 900;

/// The read timeout of a lane: `lane_s` when it sets one, else `proxy_s`, but never under
/// [`MIN_TIMEOUT_S`] when the lane sends Flex without choosing its own value.
pub fn lane_timeout_s(flex: bool, lane_s: u64, proxy_s: u64) -> u64 {
    match (flex, lane_s) {
        (true, 0) => proxy_s.max(MIN_TIMEOUT_S),
        (false, 0) => proxy_s,
        _ => lane_s,
    }
}

/// `Retry-After` as delta-seconds. The HTTP-date form and anything else is ignored: our own
/// delay stands.
fn retry_after(headers: &reqwest::header::HeaderMap) -> Option<Duration> {
    let v = headers
        .get(reqwest::header::RETRY_AFTER)?
        .to_str()
        .ok()?
        .trim();
    if v.is_empty() || !v.bytes().all(|b| b.is_ascii_digit()) {
        return None;
    }
    v.parse().ok().map(Duration::from_secs)
}

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
    /// or `None` once the policy has no retry left. The wait is the longer of our delay and the
    /// server's `Retry-After`; one that exceeds [`MAX_DELAY`] is not waited for, because the
    /// client's connection stays open meanwhile: `backoff` gives up, `default` goes to the
    /// fallback tier at once.
    fn next(&self, n: u32, server: Option<Duration>) -> Option<(Duration, Option<Bytes>)> {
        let too_long = server.is_some_and(|d| d > MAX_DELAY);
        match self.on_busy {
            OnBusy::Backoff if n < self.retries && !too_long => {
                let ours = self.backoff.saturating_mul(1u32 << n).min(MAX_DELAY);
                Some((ours.max(server.unwrap_or_default()), None))
            }
            // One try on standard processing; a second 429 there is a real rate limit.
            OnBusy::Default if n == 0 => {
                let wait = if too_long {
                    Duration::ZERO
                } else {
                    server.unwrap_or_default()
                };
                Some((wait, Some(self.fallback()?)))
            }
            _ => None,
        }
    }

    /// The body for the fallback tier: the client's own tier when `force` overwrote one, `auto`
    /// only when it sent none.
    fn fallback(&self) -> Option<Bytes> {
        let sent = serde_json::from_slice::<Value>(&self.base)
            .ok()?
            .get(TIER)
            .is_some_and(|t| !t.is_null());
        if sent {
            Some(self.base.clone())
        } else {
            set_tier(&self.base, AUTO)
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
            .ok()
            .filter(|r| r.status() == StatusCode::TOO_MANY_REQUESTS);
        let server = busy.and_then(|r| retry_after(r.headers()));
        let Some((wait, next)) = retry
            .filter(|_| busy.is_some())
            .and_then(|r| r.next(retries, server))
        else {
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

    fn waits(r: &Retry, server: Option<u64>) -> Vec<Option<(u64, bool)>> {
        let server = server.map(Duration::from_secs);
        (0..3)
            .map(|n| r.next(n, server).map(|(w, b)| (w.as_secs(), b.is_some())))
            .collect()
    }

    fn retry(on_429: &str) -> Retry {
        let mut p = flex(false, on_429);
        p.backoff_ms = 10_000;
        Retry::new(Bytes::from_static(br#"{"model":"m"}"#), &p).expect("retry")
    }

    #[test]
    fn retry_after_stretches_the_wait_but_never_shrinks_it() {
        let b = retry("backoff");
        // 10 s ours; 5 s asked is shorter, 25 s is longer than ours, 30 s is the cap itself.
        assert_eq!(waits(&b, Some(5))[0], Some((10, false)));
        assert_eq!(waits(&b, Some(25))[0], Some((25, false)));
        assert_eq!(waits(&b, Some(30))[0], Some((30, false)));
        assert_eq!(waits(&b, None)[1], Some((20, false)));
        let d = retry("default");
        assert_eq!(waits(&d, Some(7))[0], Some((7, true)));
    }

    #[test]
    fn a_retry_after_over_the_cap_skips_the_wait() {
        // backoff gives up and the 429 reaches the client.
        assert_eq!(waits(&retry("backoff"), Some(31)), [None, None, None]);
        // default retries on the fallback tier at once.
        assert_eq!(
            waits(&retry("default"), Some(3600)),
            [Some((0, true)), None, None]
        );
    }

    #[test]
    fn only_delta_seconds_are_read_from_retry_after() {
        use reqwest::header::{HeaderMap, HeaderValue, RETRY_AFTER};
        let read = |v: &str| {
            let mut h = HeaderMap::new();
            h.insert(RETRY_AFTER, HeaderValue::from_str(v).expect("header"));
            retry_after(&h)
        };
        assert_eq!(read("12"), Some(Duration::from_secs(12)));
        assert_eq!(read(" 3 "), Some(Duration::from_secs(3)));
        assert_eq!(read("0"), Some(Duration::ZERO));
        for ignored in [
            "Wed, 21 Oct 2026 07:28:00 GMT",
            "soon",
            "-5",
            "+5",
            "1.5",
            "",
            "99999999999999999999999",
        ] {
            assert_eq!(read(ignored), None, "{ignored:?}");
        }
        assert_eq!(retry_after(&HeaderMap::new()), None);
    }

    #[test]
    fn fallback_restores_the_client_tier_and_uses_auto_only_without_one() {
        let policy = flex(true, "default");
        let fallback = |body: &str| {
            let body = Bytes::from(body.to_string());
            let sent = apply("openai", &body, &policy).expect("flexed");
            assert_eq!(
                serde_json::from_slice::<Value>(&sent).unwrap()[TIER],
                "flex"
            );
            let (_, retry_body) = Retry::new(body, &policy)
                .expect("retry")
                .next(0, None)
                .expect("one retry");
            let v: Value = serde_json::from_slice(&retry_body.expect("body")).unwrap();
            v[TIER].clone()
        };
        assert_eq!(
            fallback(r#"{"model":"m","service_tier":"priority"}"#),
            "priority"
        );
        assert_eq!(fallback(r#"{"model":"m"}"#), "auto");
        assert_eq!(fallback(r#"{"model":"m","service_tier":null}"#), "auto");
    }

    #[test]
    fn a_flex_lane_reads_for_at_least_the_guides_fifteen_minutes() {
        assert_eq!(lane_timeout_s(true, 0, 600), 900);
        assert_eq!(lane_timeout_s(true, 0, 1800), 1800);
        // A value the operator set on the lane is theirs.
        assert_eq!(lane_timeout_s(true, 120, 600), 120);
        assert_eq!(lane_timeout_s(false, 0, 600), 600);
        assert_eq!(lane_timeout_s(false, 45, 600), 45);
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
            .map(|n| r.next(n, None).map(|(w, b)| (w.as_secs(), b)))
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
        let (wait, body) = d.next(0, None).expect("one retry");
        assert_eq!(wait, Duration::ZERO);
        assert_eq!(
            body.expect("auto body"),
            r#"{"service_tier":"auto","model":"m"}"#
        );
        assert!(d.next(1, None).is_none());
    }
}
