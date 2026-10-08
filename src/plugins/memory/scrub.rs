// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! Secret scrubbing before any stored observation narrative (T454).
//!
//! Adapted from agentmemory `src/functions/privacy.ts` `stripPrivateData` (Apache-2.0,
//! https://github.com/rohitg00/agentmemory v0.9.30). Patterns and replacement tokens match
//! that source; this is a native reimplementation, not a copy of the TypeScript file.

use std::sync::LazyLock;

use regex::Regex;

static PRIVATE_TAG: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"(?is)<private>.*?</private>").expect("private tag"));

static PRIVATE_KEY_BLOCK: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(
        r"(?s)-----BEGIN [A-Z0-9 ]*PRIVATE KEY(?: BLOCK)?-----.*?(?:-----END [A-Z0-9 ]*PRIVATE KEY(?: BLOCK)?-----|\z)",
    )
    .expect("pem block")
});

static URL_CREDENTIALS: LazyLock<Regex> = LazyLock::new(|| {
    // Delimit with `#` so the class may hold both `'` and `"`.
    Regex::new(r#"(?i)\b([a-z][a-z0-9+.-]*://)[^\s/?#@:"'<>]*:[^\s/?#@"'<>]+@"#)
        .expect("url credentials")
});

/// Key-shaped and labelled secret tokens, same order as agentmemory's `SECRET_PATTERN_SOURCES`.
static SECRET_PATTERNS: LazyLock<[Regex; 14]> = LazyLock::new(|| {
    [
        // Optional quotes after the key so JSON `"api_key":"…"` matches as well as `api_key=…`.
        Regex::new(
            r#"(?i)(?:api[_-]?key|secret|token|password|credential|auth)["']?\s*[=:]\s*["']?[A-Za-z0-9_\-/.+]{20,}["']?"#,
        )
        .expect("labelled secret"),
        Regex::new(r"(?i)Bearer\s+[A-Za-z0-9._\-+/=]{20,}").expect("bearer"),
        Regex::new(r"sk-proj-[A-Za-z0-9\-_]{20,}").expect("sk-proj"),
        Regex::new(r"(?:sk|pk|rk|ak)-[A-Za-z0-9][A-Za-z0-9\-_]{19,}").expect("sk-family"),
        Regex::new(r"sk-ant-[A-Za-z0-9\-_]{20,}").expect("sk-ant"),
        Regex::new(r"gh[pus]_[A-Za-z0-9]{36,}").expect("github token"),
        Regex::new(r"github_pat_[A-Za-z0-9_]{22,}").expect("github_pat"),
        Regex::new(r"xoxb-[A-Za-z0-9\-]+").expect("slack"),
        Regex::new(r"AKIA[0-9A-Z]{16}").expect("aws"),
        Regex::new(r"AIza[A-Za-z0-9\-_]{35}").expect("google"),
        Regex::new(r"eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}")
            .expect("jwt"),
        Regex::new(r"npm_[A-Za-z0-9]{36}").expect("npm"),
        Regex::new(r"glpat-[A-Za-z0-9\-_]{20,}").expect("gitlab"),
        Regex::new(r"dop_v1_[A-Za-z0-9]{64}").expect("digitalocean"),
    ]
});

/// Adapted from agentmemory `src/functions/privacy.ts` `stripPrivateData` (Apache-2.0).
pub fn strip_private(input: &str) -> String {
    let mut result = PRIVATE_TAG.replace_all(input, "[REDACTED]").into_owned();
    result = PRIVATE_KEY_BLOCK
        .replace_all(&result, "[REDACTED_SECRET]")
        .into_owned();
    result = URL_CREDENTIALS
        .replace_all(&result, "${1}[REDACTED_SECRET]@")
        .into_owned();
    for pattern in SECRET_PATTERNS.iter() {
        result = pattern
            .replace_all(&result, "[REDACTED_SECRET]")
            .into_owned();
    }
    result
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn private_tags_become_redacted() {
        assert_eq!(
            strip_private("before <private>secret sauce</private> after"),
            "before [REDACTED] after"
        );
    }

    #[test]
    fn pem_blocks_are_redacted() {
        let pem = "-----BEGIN RSA PRIVATE KEY-----\nMIIE\n-----END RSA PRIVATE KEY-----";
        assert_eq!(strip_private(pem), "[REDACTED_SECRET]");
    }

    #[test]
    fn url_userinfo_is_redacted() {
        assert_eq!(
            strip_private("clone https://user:hunter2@example.com/r.git"),
            "clone https://[REDACTED_SECRET]@example.com/r.git"
        );
    }

    #[test]
    fn labelled_and_key_shaped_tokens_are_redacted() {
        let out = strip_private(
            "api_key=abcdefghijklmnopqrstuvwxyz12 Bearer abcdefghijklmnopqrstuv sk-ant-abcdefghijklmnopqrst",
        );
        assert!(!out.contains("abcdefghijklmnopqrstuvwxyz12"), "{out}");
        assert!(!out.contains("abcdefghijklmnopqrstuv"), "{out}");
        assert!(!out.contains("sk-ant-abcdefghijklmnopqrst"), "{out}");
        assert!(out.contains("[REDACTED_SECRET]"), "{out}");
    }

    #[test]
    fn plain_text_is_unchanged() {
        assert_eq!(strip_private("read src/main.rs"), "read src/main.rs");
    }
}
