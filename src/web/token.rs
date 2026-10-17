// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! S2: the `/ws` access token. `rtok web` issues a fresh random token on every start and writes
//! it to `<RTOK_HOME>/web-token`, readable by the owning user only. Local clients read that file
//! and present the token; a host that cannot read the file cannot connect. See `docs/web-auth.md`.

use std::io::Write;
use std::path::{Path, PathBuf};

use anyhow::{Context, Result};
use subtle::ConstantTimeEq;

/// File name of the token inside the rtok home.
pub const TOKEN_FILE: &str = "web-token";

/// Where the server of `home` keeps its token.
pub fn token_path(home: &Path) -> PathBuf {
    home.join(TOKEN_FILE)
}

/// 256 bits from the OS CSPRNG (UUID v4 draws its 122 random bits from it), as 64 hex chars.
fn generate() -> String {
    format!(
        "{}{}",
        uuid::Uuid::new_v4().simple(),
        uuid::Uuid::new_v4().simple()
    )
}

/// Generate a new token and store it for `home`, replacing the previous one. The file is
/// created with its restrictive permissions already in place (never world-readable, not even
/// for an instant) and swapped in with a rename, so a reader sees the old token or the new one.
pub fn issue(home: &Path) -> Result<String> {
    std::fs::create_dir_all(home).with_context(|| format!("create {}", home.display()))?;
    let token = generate();
    let path = token_path(home);
    let tmp = home.join(format!("{TOKEN_FILE}.{}.tmp", std::process::id()));
    let _ = std::fs::remove_file(&tmp);
    let written = write_private(&tmp, token.as_bytes());
    if let Err(e) = written.and_then(|()| std::fs::rename(&tmp, &path)) {
        let _ = std::fs::remove_file(&tmp);
        return Err(e).with_context(|| format!("write {}", path.display()));
    }
    Ok(token)
}

/// The token a running server of `home` issued; what a local client sends.
pub fn read(home: &Path) -> Result<String> {
    let path = token_path(home);
    let raw = std::fs::read_to_string(&path)
        .with_context(|| format!("read {} (is `rtok web` running?)", path.display()))?;
    Ok(raw.trim().to_owned())
}

/// Constant-time equality, so the comparison does not leak how much of a guess matched.
pub fn matches(expected: &str, presented: &str) -> bool {
    expected.as_bytes().ct_eq(presented.as_bytes()).into()
}

#[cfg(unix)]
fn write_private(path: &Path, bytes: &[u8]) -> std::io::Result<()> {
    use std::os::unix::fs::OpenOptionsExt;
    let mut f = std::fs::OpenOptions::new()
        .write(true)
        .create_new(true)
        .mode(0o600)
        .open(path)?;
    f.write_all(bytes)
}

/// Windows: the file inherits the home's ACL, so inheritance is cut and only the current user
/// is granted access (`icacls`, no extra dependency) before the secret is written. A failure
/// is an error: a token file others can read would be worse than no server.
#[cfg(windows)]
fn write_private(path: &Path, bytes: &[u8]) -> std::io::Result<()> {
    let mut f = std::fs::OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(path)?;
    restrict_to_current_user(path)?;
    f.write_all(bytes)
}

#[cfg(windows)]
fn restrict_to_current_user(path: &Path) -> std::io::Result<()> {
    let user = std::env::var("USERNAME").map_err(|_| {
        std::io::Error::other("USERNAME is not set; cannot restrict the token file")
    })?;
    let domain = std::env::var("USERDOMAIN").ok();
    let account = domain.map_or(user.clone(), |d| format!("{d}\\{user}"));
    let out = std::process::Command::new("icacls")
        .arg(path)
        .args(["/inheritance:r", "/grant:r"])
        .arg(format!("{account}:(F)"))
        .output()?;
    if out.status.success() {
        Ok(())
    } else {
        Err(std::io::Error::other(format!(
            "icacls failed: {}",
            String::from_utf8_lossy(&out.stdout)
        )))
    }
}

#[cfg(not(any(unix, windows)))]
fn write_private(path: &Path, bytes: &[u8]) -> std::io::Result<()> {
    let mut f = std::fs::OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(path)?;
    f.write_all(bytes)
}

/// The token a client presented: `Authorization: Bearer <token>`, or `?token=<token>` for a
/// browser, whose WebSocket API cannot set headers.
pub fn presented(headers: &axum::http::HeaderMap, query: Option<&str>) -> Option<String> {
    if let Some(v) = headers
        .get(axum::http::header::AUTHORIZATION)
        .and_then(|v| v.to_str().ok())
    {
        let (scheme, rest) = v.split_once(' ')?;
        return scheme
            .eq_ignore_ascii_case("bearer")
            .then(|| rest.trim().to_owned());
    }
    query?
        .split('&')
        .find_map(|kv| kv.strip_prefix("token="))
        .map(str::to_owned)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn tokens_are_distinct_and_hex() {
        let (a, b) = (generate(), generate());
        assert_ne!(a, b);
        assert_eq!(a.len(), 64);
        assert!(a.bytes().all(|c| c.is_ascii_hexdigit()));
    }

    #[test]
    fn compare_is_exact() {
        assert!(matches("abc", "abc"));
        assert!(!matches("abc", "abd"));
        assert!(!matches("abc", "abcd"));
        assert!(!matches("abc", ""));
    }

    #[test]
    fn header_wins_and_query_is_the_fallback() {
        let mut h = axum::http::HeaderMap::new();
        assert_eq!(presented(&h, Some("x=1&token=q")).as_deref(), Some("q"));
        assert_eq!(presented(&h, None), None);
        h.insert("authorization", "Bearer hdr".parse().unwrap());
        assert_eq!(presented(&h, Some("token=q")).as_deref(), Some("hdr"));
        h.insert("authorization", "Basic zzz".parse().unwrap());
        assert_eq!(presented(&h, Some("token=q")), None);
    }
}
