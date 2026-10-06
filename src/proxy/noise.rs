// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! T432: terminal noise out of the request before it goes upstream. Tool results and user
//! text blocks lose ANSI escapes, control and zero-width characters, through the same
//! cleaner the store applies to saved bodies (T431), narrowed to those characters.
//! Whitespace and harness wrappers stay: the model's exact-match edits depend on the first,
//! and the second are instructions to it.

use axum::body::Bytes;
use serde_json::Value;

use super::Wire;
use crate::sanitize;

/// `original` with the noise cut from every tool result and user text block. Pure, so the
/// same history gives the same bytes on every turn and the prompt cache keeps hitting.
/// Fail open: an unparseable or already clean body is forwarded as it arrived.
pub(super) fn strip(dialect: &dyn Wire, original: Bytes) -> Bytes {
    let Ok(mut body) = serde_json::from_slice::<Value>(&original) else {
        return original;
    };
    let mut changed = false;
    for result in dialect.tool_results(&mut body) {
        changed |= sanitize::strings(result.content, sanitize::terminal_noise);
    }
    for blob in dialect.live_blobs(&mut body) {
        changed |= sanitize::strings(blob.content, sanitize::terminal_noise);
    }
    if !changed {
        return original;
    }
    serde_json::to_vec(&body).map_or(original, Bytes::from)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::proxy::anthropic::ANTHROPIC;
    use crate::proxy::openai_chat::OPENAI_CHAT;
    use serde_json::json;

    const NOISY: &str = "\u{1b}[31mFAIL\u{1b}[0m a\u{200b}b\u{7} \r\n";
    const CLEAN: &str = "FAIL ab \r\n";

    fn stripped(dialect: &dyn Wire, body: &Value) -> Value {
        let out = strip(dialect, Bytes::from(serde_json::to_vec(body).unwrap()));
        serde_json::from_slice(&out).unwrap()
    }

    #[test]
    fn anthropic_results_and_user_text_are_cleaned() {
        let body = json!({"model": "m", "n": 1.5, "messages": [
            {"role": "user", "content": [
                {"type": "tool_result", "tool_use_id": "t", "content": NOISY},
                {"type": "tool_result", "tool_use_id": "u", "content": [{"type": "text", "text": NOISY}]},
                {"type": "text", "text": NOISY}
            ]},
            {"role": "assistant", "content": [{"type": "text", "text": NOISY}]}
        ]});
        let got = stripped(&ANTHROPIC, &body);
        let blocks = &got["messages"][0]["content"];
        assert_eq!(blocks[0]["content"], CLEAN);
        assert_eq!(blocks[1]["content"][0]["text"], CLEAN);
        assert_eq!(blocks[2]["text"], CLEAN);
        // The model's own turns are never rewritten: thinking blocks are signed.
        assert_eq!(got["messages"][1], body["messages"][1]);
        assert_eq!((&got["model"], &got["n"]), (&body["model"], &body["n"]));
    }

    #[test]
    fn chat_tool_messages_are_cleaned() {
        let body = json!({"messages": [{"role": "tool", "tool_call_id": "c", "content": NOISY}]});
        let got = stripped(&OPENAI_CHAT, &body);
        assert_eq!(got["messages"][0]["content"], CLEAN);
    }

    #[test]
    fn a_clean_or_unparseable_body_keeps_its_bytes() {
        // Spacing a re-serialisation would change.
        let clean = Bytes::from_static(
            br#"{ "messages": [ {"role":"user","content":[{"type":"tool_result","tool_use_id":"t","content":"ok\r\n  "}]} ] }"#,
        );
        assert_eq!(strip(&ANTHROPIC, clean.clone()), clean);
        let junk = Bytes::from_static(b"\x1b[31mnot json");
        assert_eq!(strip(&ANTHROPIC, junk.clone()), junk);
    }

    #[test]
    fn stripping_twice_changes_nothing() {
        let body = json!({"messages": [
            {"role": "user", "content": [{"type": "tool_result", "tool_use_id": "t", "content": NOISY}]}
        ]});
        let once = strip(&ANTHROPIC, Bytes::from(serde_json::to_vec(&body).unwrap()));
        assert_eq!(strip(&ANTHROPIC, once.clone()), once);
    }
}
