// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! Shared JSONC-safe surgical editor (T79, T117): add, replace, or remove one string-keyed
//! member of a top-level object in a settings file that must keep its comments and trailing
//! commas intact. Zed's `context_servers.<name>` and VS Code's `chat.pluginLocations.<path>`
//! are the same shape — one nested object, one member keyed by name — so both installers go
//! through this instead of each carrying its own text scanner (no duplicated logic).
//!
//! The editor works on byte spans of the original text; a JSONC copy (`jsonc-parser`) is
//! parsed only to validate and to compare values, never written back (T79).

use std::path::Path;

use anyhow::{Context, Result};
use serde_json::Value;

use crate::config::Config;

/// Parse JSONC (comments, trailing commas) into a `Value`. Validation/lookup only — the
/// surgical editor below works on spans of the original text, never this parsed copy.
pub fn parse(raw: &str) -> Result<Value> {
    let opts = jsonc_parser::ParseOptions {
        allow_comments: true,
        allow_trailing_commas: true,
        allow_loose_object_property_names: false,
        allow_missing_commas: false,
        allow_single_quoted_strings: false,
        allow_hexadecimal_numbers: false,
        allow_unary_plus_numbers: false,
        // 0.34 adds JSON5. Stay on comments and trailing commas only.
        allow_bare_decimal_point_numbers: false,
        allow_non_finite_numbers: false,
        allow_extended_string_escapes: false,
    };
    jsonc_parser::parse_to_serde_value::<Value>(raw, &opts).map_err(|e| anyhow::anyhow!("{e}"))
}

fn parse_at(raw: &str, path: &Path) -> Result<Value> {
    parse(raw).with_context(|| path.display().to_string())
}

/// An absent file reads as empty (callers start fresh); any other read error is fatal —
/// never overwrite a config that could not be read.
pub fn read_or_empty(path: &Path) -> Result<String> {
    match std::fs::read_to_string(path) {
        Ok(raw) => Ok(raw),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(String::new()),
        Err(e) => Err(e).with_context(|| path.display().to_string()),
    }
}

/// `raw` with `//…` and `/*…*/` outside strings removed (positions shift; parse only).
pub fn strip_comments(raw: &str) -> String {
    let bytes = raw.as_bytes();
    let mut out = String::with_capacity(raw.len());
    let mut i = 0;
    while i < bytes.len() {
        match scan_string(bytes, i) {
            Some(end) => {
                out.push_str(&raw[i..end]);
                i = end;
            }
            None => {
                if bytes[i..].starts_with(b"//") {
                    while i < bytes.len() && bytes[i] != b'\n' {
                        i += 1;
                    }
                } else if bytes[i..].starts_with(b"/*") {
                    while i < bytes.len() && !bytes[i..].starts_with(b"*/") {
                        i += 1;
                    }
                    i = (i + 2).min(bytes.len());
                } else {
                    let ch = raw[i..].chars().next().unwrap_or('\0');
                    out.push(ch);
                    i += ch.len_utf8();
                }
            }
        }
    }
    out
}

/// End (exclusive) of the `"`-string starting at `i`, or `None` when `i` is not a quote.
fn scan_string(bytes: &[u8], i: usize) -> Option<usize> {
    if bytes.get(i) != Some(&b'"') {
        return None;
    }
    let mut j = i + 1;
    while j < bytes.len() {
        match bytes[j] {
            b'\\' => j += 2,
            b'"' => return Some(j + 1),
            _ => j += 1,
        }
    }
    None
}

/// Skip whitespace and comments from `i`.
fn skip_trivia(text: &str, mut i: usize) -> usize {
    let bytes = text.as_bytes();
    loop {
        while i < bytes.len() && bytes[i].is_ascii_whitespace() {
            i += 1;
        }
        if bytes[i..].starts_with(b"//") {
            while i < bytes.len() && bytes[i] != b'\n' {
                i += 1;
            }
        } else if bytes[i..].starts_with(b"/*") {
            while i < bytes.len() && !bytes[i..].starts_with(b"*/") {
                i += 1;
            }
            i = (i + 2).min(bytes.len());
        } else {
            return i;
        }
    }
}

/// End (exclusive) of the JSON value starting at `i` (after trivia), or `None`.
fn skip_value(text: &str, i: usize) -> Option<usize> {
    let bytes = text.as_bytes();
    let i = skip_trivia(text, i);
    match bytes.get(i)? {
        b'{' => match_pair(text, i, b'{', b'}'),
        b'[' => match_pair(text, i, b'[', b']'),
        b'"' => scan_string(bytes, i),
        _ => {
            let mut j = i;
            // A literal holds no whitespace or `/`, so it stops before a trailing comment and
            // that comment stays outside the span a replace overwrites.
            while j < bytes.len()
                && !bytes[j].is_ascii_whitespace()
                && !matches!(bytes[j], b',' | b'}' | b']' | b'/')
            {
                j += 1;
            }
            // A literal must end before a delimiter, not at end of input.
            (j > i && skip_trivia(text, j) < bytes.len()).then_some(j)
        }
    }
}

/// End (exclusive) of the bracketed value opening at `i`, or `None` when unbalanced.
fn match_pair(text: &str, i: usize, open: u8, close: u8) -> Option<usize> {
    let bytes = text.as_bytes();
    let mut depth = 0;
    let mut j = i;
    while j < bytes.len() {
        if let Some(end) = scan_string(bytes, j) {
            j = end;
            continue;
        }
        if bytes[j..].starts_with(b"//") || bytes[j..].starts_with(b"/*") {
            j = skip_trivia(text, j);
            continue;
        }
        match bytes[j] {
            b if b == open => depth += 1,
            b if b == close => {
                depth -= 1;
                if depth == 0 {
                    return Some(j + 1);
                }
            }
            _ => {}
        }
        j += 1;
    }
    None
}

/// Decoded key of the string spanning `start..end` (exclusive end, quotes included).
fn key_name(text: &str, start: usize, end: usize) -> Option<String> {
    serde_json::from_str(&text[start..end]).ok()
}

/// `(key_start, value_start, value_end)` of `key` in the object opening at `open`.
fn find_key(text: &str, open: usize, key: &str) -> Option<(usize, usize, usize)> {
    let mut i = skip_trivia(text, open + 1);
    loop {
        if text.as_bytes().get(i) == Some(&b'}') {
            return None;
        }
        let ks = i;
        let ke = scan_string(text.as_bytes(), i)?;
        let name = key_name(text, ks, ke)?;
        i = skip_trivia(text, ke);
        if text.as_bytes().get(i) != Some(&b':') {
            return None;
        }
        let vs = skip_trivia(text, i + 1);
        let ve = skip_value(text, vs)?;
        if name == key {
            return Some((ks, vs, ve));
        }
        i = skip_trivia(text, ve);
        if text.as_bytes().get(i) == Some(&b',') {
            i = skip_trivia(text, i + 1);
        } else {
            return None;
        }
    }
}

/// Byte offset of the root object's `{`, or `None`.
fn root_open(text: &str) -> Option<usize> {
    let i = skip_trivia(text, 0);
    (text.as_bytes().get(i) == Some(&b'{')).then_some(i)
}

/// End of the last member value in the object opening at `open`.
fn last_value_end(text: &str, open: usize) -> Option<usize> {
    let mut i = skip_trivia(text, open + 1);
    let mut last = None;
    loop {
        if text.as_bytes().get(i) == Some(&b'}') {
            return last;
        }
        let ke = scan_string(text.as_bytes(), i)?;
        i = skip_trivia(text, ke);
        if text.as_bytes().get(i) != Some(&b':') {
            return last;
        }
        let ve = skip_value(text, skip_trivia(text, i + 1))?;
        last = Some(ve);
        i = skip_trivia(text, ve);
        if text.as_bytes().get(i) == Some(&b',') {
            i = skip_trivia(text, i + 1);
        } else {
            return last;
        }
    }
}

/// Remove the member spanning `key_start..value_end` plus one adjacent comma.
fn excise_member(text: &str, key_start: usize, value_end: usize) -> String {
    let bytes = text.as_bytes();
    // Prefer the preceding comma, so the survivors keep their separators.
    let mut back = key_start;
    while back > 0 && bytes[back - 1].is_ascii_whitespace() {
        back -= 1;
    }
    if back > 0 && bytes[back - 1] == b',' {
        return format!("{}{}", &text[..back - 1], &text[value_end..]);
    }
    let mut fwd = value_end;
    while fwd < bytes.len() && bytes[fwd].is_ascii_whitespace() {
        fwd += 1;
    }
    if bytes.get(fwd) == Some(&b',') {
        return format!("{}{}", &text[..key_start], &text[fwd + 1..]);
    }
    format!("{}{}", &text[..key_start], &text[value_end..])
}

/// Render `entry` indented by `pad` spaces per level below its key line.
fn render_entry(entry: &Value, pad: usize) -> String {
    serde_json::to_string_pretty(entry)
        .unwrap_or_default()
        .lines()
        .enumerate()
        .map(|(n, l)| {
            if n == 0 {
                l.to_string()
            } else {
                format!("{}{l}", " ".repeat(pad))
            }
        })
        .collect::<Vec<_>>()
        .join("\n")
}

/// A JSON string literal for `s`, properly escaped — a plugin path may hold backslashes
/// (Windows) or other characters that a naive `"{s}"` would write out broken.
fn qkey(s: &str) -> String {
    serde_json::to_string(s).unwrap_or_else(|_| format!("\"{s}\""))
}

/// A whole new document carrying only `top_key.entry_key`.
fn fresh_doc(top_key: &str, entry_key: &str, entry: &Value) -> String {
    let mut inner = serde_json::Map::new();
    inner.insert(entry_key.to_string(), entry.clone());
    let mut root = serde_json::Map::new();
    root.insert(top_key.to_string(), Value::Object(inner));
    serde_json::to_string_pretty(&Value::Object(root)).unwrap_or_default() + "\n"
}

/// What [`upsert_member`] did.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Upsert {
    NoChange,
    Added,
    Replaced,
}

/// Add or replace `raw[top_key][entry_key] = entry`, preserving every comment, trailing
/// comma and foreign member. A missing or blank file starts as `{}`; a root that parses but
/// is not an object (or whose `top_key` value is not an object) is replaced outright — there
/// is nowhere to add a member — never silently dropped otherwise.
pub fn upsert_member(
    raw: &str,
    path: &Path,
    top_key: &str,
    entry_key: &str,
    entry: &Value,
) -> Result<(String, Upsert)> {
    let text = if strip_comments(raw).trim().is_empty() {
        String::from("{}")
    } else {
        let root = parse_at(raw, path)?;
        if !root.is_object() {
            return Ok((fresh_doc(top_key, entry_key, entry), Upsert::Added));
        }
        raw.to_string()
    };
    let open = root_open(&text).with_context(|| path.display().to_string())?;
    let close = match_pair(&text, open, b'{', b'}').with_context(|| path.display().to_string())?;
    let brace = close - 1;
    match find_key(&text, open, top_key) {
        None => {
            let inner = strip_comments(&text[open + 1..brace]);
            let trimmed = inner.trim();
            // One separator, never two: a root that already ends in a trailing comma
            // keeps it and gains no second one (T79).
            let sep = if trimmed.is_empty() || trimmed.ends_with(',') {
                "\n  "
            } else {
                ",\n  "
            };
            let mut body = text;
            body.replace_range(
                brace..close,
                &format!(
                    "{sep}{}: {{\n    {}: {}\n  }}\n}}",
                    qkey(top_key),
                    qkey(entry_key),
                    render_entry(entry, 4)
                ),
            );
            Ok((body, Upsert::Added))
        }
        Some((_, vs, ve)) => {
            let span: Value = parse(&text[vs..ve]).unwrap_or(Value::Null);
            if !span.is_object() {
                let mut body = text;
                body.replace_range(
                    vs..ve,
                    &format!(
                        "{{\n    {}: {}\n  }}",
                        qkey(entry_key),
                        render_entry(entry, 4)
                    ),
                );
                return Ok((body, Upsert::Added));
            }
            match find_key(&text, vs, entry_key) {
                None => {
                    let inner_empty = strip_comments(&text[vs + 1..ve.saturating_sub(1)])
                        .trim()
                        .is_empty();
                    let last = last_value_end(&text, vs).unwrap_or(ve - 1);
                    let mut body = text;
                    if inner_empty {
                        body.insert_str(
                            ve - 1,
                            &format!("\n    {}: {}\n  ", qkey(entry_key), render_entry(entry, 4)),
                        );
                    } else {
                        body.insert_str(
                            last,
                            &format!(",\n    {}: {}", qkey(entry_key), render_entry(entry, 4)),
                        );
                    }
                    Ok((body, Upsert::Added))
                }
                Some((_, evs, eve)) => {
                    let have: Value = parse(&text[evs..eve]).unwrap_or(Value::Null);
                    if have == *entry {
                        Ok((text, Upsert::NoChange))
                    } else {
                        let mut body = text;
                        body.replace_range(evs..eve, &render_entry(entry, 4));
                        Ok((body, Upsert::Replaced))
                    }
                }
            }
        }
    }
}

/// Drop `raw[top_key][entry_key]`, keeping every comment and foreign member. An object left
/// with no entries and no comments goes with its key; a comment-only object stays, so user
/// comments are never destroyed. `true` when a member was actually removed.
pub fn remove_member(
    raw: &str,
    path: &Path,
    top_key: &str,
    entry_key: &str,
) -> Result<(String, bool)> {
    if strip_comments(raw).trim().is_empty() {
        return Ok((raw.to_string(), false));
    }
    let root = parse_at(raw, path)?;
    if !root.is_object() {
        return Ok((raw.to_string(), false));
    }
    let open = root_open(raw).unwrap_or(0);
    let Some((_, vs, _)) = find_key(raw, open, top_key) else {
        return Ok((raw.to_string(), false));
    };
    let Some((rks, _, rve)) = find_key(raw, vs, entry_key) else {
        return Ok((raw.to_string(), false));
    };
    let body = excise_member(raw, rks, rve);
    let drop_key = match find_key(&body, root_open(&body).unwrap_or(0), top_key) {
        Some((_, cvs, cve)) => is_blank(&body[cvs + 1..cve.saturating_sub(1)]),
        None => false,
    };
    let mut body = body;
    if drop_key {
        let (cks, _, cve) = find_key(&body, root_open(&body).unwrap_or(0), top_key).unwrap();
        body = excise_member(&body, cks, cve);
    }
    Ok((body, true))
}

/// [`upsert_member`] on a host's JSONC file, written under the run's `apply` gates. `report`
/// words the outcome, because each host prints its own line shape; the document is parsed
/// back before it is written, so a malformed result never reaches the file.
pub(crate) fn upsert_entry(
    cfg: &Config,
    path: &Path,
    top_key: &str,
    name: &str,
    entry: &Value,
    report: impl FnOnce(Upsert) -> String,
) -> Result<String> {
    let raw = read_or_empty(path)?;
    let (body, edit) = upsert_member(&raw, path, top_key, name, entry)?;
    parse(&body).with_context(|| path.display().to_string())?;
    let report = match edit {
        Upsert::NoChange => rtok_agent_sdk::NO_CHANGES.to_string(),
        edit => report(edit),
    };
    rtok_agent_sdk::write(&super::apply(cfg), path, &body, &report)?;
    Ok(report)
}

/// [`remove_member`] on a host's JSONC file, but only as far as rtok wrote it:
/// [`rtok_agent_sdk::judge_owned`] (T246, T246.5) leaves an entry that does not run the rtok
/// binary, or one the user changed from `ours`, unless `--yes` says remove. A malformed
/// document is left for [`remove_member`] to error on.
pub(crate) fn remove_entry(
    cfg: &Config,
    path: &Path,
    top_key: &str,
    name: &str,
    ours: &Value,
) -> Result<String> {
    let raw = read_or_empty(path)?;
    if let Ok(root) = parse(&raw)
        && let Some(have) = root.get(top_key).and_then(|t| t.get(name))
    {
        let at = format!("{top_key}.{name} in {}", path.display());
        if let Some(leave) =
            rtok_agent_sdk::judge_owned(&super::apply(cfg), &at, have, ours, super::is_rtok_bin)
        {
            return Ok(leave);
        }
    }
    let (body, removed) = remove_member(&raw, path, top_key, name)?;
    parse(&body).with_context(|| path.display().to_string())?;
    let report = if removed {
        format!("- {top_key}.{name}")
    } else {
        rtok_agent_sdk::NO_CHANGES.to_string()
    };
    rtok_agent_sdk::write(&super::apply(cfg), path, &body, &report)?;
    Ok(report)
}

/// One step of a path into a JSONC document.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Seg<'a> {
    Key(&'a str),
    Index(usize),
}

/// `(value_start, value_end)` of element `n` of the array opening at `open`, and where the
/// element starts for an excision (the same offset: elements have no key).
fn nth_element(text: &str, open: usize, n: usize) -> Option<(usize, usize)> {
    let mut i = skip_trivia(text, open + 1);
    for k in 0.. {
        if text.as_bytes().get(i) == Some(&b']') {
            return None;
        }
        let ve = skip_value(text, i)?;
        if k == n {
            return Some((i, ve));
        }
        i = skip_trivia(text, ve);
        if text.as_bytes().get(i) == Some(&b',') {
            i = skip_trivia(text, i + 1);
        } else {
            return None;
        }
    }
    None
}

/// `(member_start, value_start, value_end)` of the value at `segs`; for an array element the
/// member starts where its value does.
fn locate(text: &str, segs: &[Seg]) -> Option<(usize, usize, usize)> {
    let start = skip_trivia(text, 0);
    let mut at = (start, start, skip_value(text, start)?);
    for seg in segs {
        let open = at.1;
        at = match (seg, text.as_bytes().get(open)?) {
            (Seg::Key(k), b'{') => find_key(text, open, k)?,
            (Seg::Index(n), b'[') => {
                let (vs, ve) = nth_element(text, open, *n)?;
                (vs, vs, ve)
            }
            _ => return None,
        };
    }
    Some(at)
}

/// Whether `inner` (the text between a container's brackets) is blank: no member, no element
/// and no comment, so dropping the container destroys nothing the user wrote.
fn is_blank(inner: &str) -> bool {
    let stripped = strip_comments(inner);
    stripped.trim().is_empty() && stripped.len() == inner.len()
}

/// Drop the member or array element at `segs`, with one adjacent comma, and nothing else: every
/// other byte of `raw` stays. `false` when the path leads nowhere.
pub fn remove_at(raw: &str, path: &Path, segs: &[Seg]) -> Result<(String, bool)> {
    parse_at(raw, path)?;
    let Some((ms, _, ve)) = locate(raw, segs) else {
        return Ok((raw.to_string(), false));
    };
    Ok((excise_member(raw, ms, ve), true))
}

/// Whether the object or array at `segs` holds nothing, not even a comment.
pub fn is_empty_at(raw: &str, segs: &[Seg]) -> bool {
    locate(raw, segs).is_some_and(|(_, vs, ve)| is_blank(&raw[vs + 1..ve.saturating_sub(1)]))
}

/// Insert `"key": value` as the last member of the object opening at `open`, `pad` spaces deep.
fn push_member(text: &mut String, open: usize, key: &str, value: &Value, pad: usize) {
    let close = skip_value(text, open).unwrap_or(text.len());
    let brace = close - 1;
    let member = format!("{}: {}", qkey(key), render_entry(value, pad));
    let outer = " ".repeat(pad.saturating_sub(2));
    let ind = " ".repeat(pad);
    let inner = &text[open + 1..brace];
    if is_blank(inner) {
        text.replace_range(open + 1..brace, &format!("\n{ind}{member}\n{outer}"));
    } else if let Some(last) = last_value_end(text, open) {
        text.insert_str(last, &format!(",\n{ind}{member}"));
    } else {
        text.insert_str(brace, &format!("\n{ind}{member}\n{outer}"));
    }
}

/// Append `item` to the array opening at `open` (its `]` closes at `end`), in the array's own
/// style: one element per line, or inline after the last one.
fn push_element(text: &mut String, open: usize, end: usize, item: &Value) {
    let line = text[..open].rfind('\n').map_or(0, |n| n + 1);
    let base: String = text[line..]
        .chars()
        .take_while(|c| c.is_whitespace())
        .collect();
    let (mut n, mut last) = (0, None);
    while let Some(span) = nth_element(text, open, n) {
        last = Some(span);
        n += 1;
    }
    let Some((start, stop)) = last else {
        let pad = base.len() + 2;
        let at = open + 1..end - 1;
        let blank = is_blank(&text[at.clone()]);
        let body = format!("\n{}{}\n{base}", " ".repeat(pad), render_entry(item, pad));
        if blank {
            text.replace_range(at, &body);
        } else {
            text.insert_str(end - 1, &body);
        }
        return;
    };
    let before = &text[text[..start].rfind('\n').map_or(0, |n| n + 1)..start];
    let add = if before.trim().is_empty() {
        format!(",\n{before}{}", render_entry(item, before.len()))
    } else {
        format!(", {}", serde_json::to_string(item).unwrap_or_default())
    };
    text.insert_str(stop, &add);
}

/// Add `item` to the array at `raw[keys[0]]…[keys[n]]`, creating the objects and the array that
/// are missing. Every other byte of `raw` stays: comments, trailing commas, indentation. A
/// value of another shape on the way is an error, never replaced.
pub fn push_item(raw: &str, path: &Path, keys: &[&str], item: &Value) -> Result<String> {
    anyhow::ensure!(!keys.is_empty(), "push_item needs a key");
    let mut text = if strip_comments(raw).trim().is_empty() {
        String::from("{}")
    } else {
        parse_at(raw, path)?;
        raw.to_string()
    };
    let mut open =
        root_open(&text).with_context(|| format!("{}: not a JSON object", path.display()))?;
    for (depth, key) in keys.iter().enumerate() {
        let Some((_, vs, ve)) = find_key(&text, open, key) else {
            let value = keys[depth + 1..]
                .iter()
                .rev()
                .fold(Value::Array(vec![item.clone()]), |v, k| {
                    Value::Object([(k.to_string(), v)].into_iter().collect())
                });
            push_member(&mut text, open, key, &value, 2 * (depth + 1));
            return Ok(text);
        };
        let want = if depth + 1 == keys.len() { b'[' } else { b'{' };
        anyhow::ensure!(
            text.as_bytes()[vs] == want,
            "{}: `{key}` is not an {}",
            path.display(),
            if want == b'[' { "array" } else { "object" }
        );
        if want == b'[' {
            push_element(&mut text, vs, ve, item);
            return Ok(text);
        }
        open = vs;
    }
    unreachable!("the last key returns")
}

/// Drop the elements `ours` accepts from the array at `keys`, then the array and every object
/// that leaves empty (nothing else in them, not even a comment). The count of dropped elements.
pub fn pull_items(
    raw: &str,
    path: &Path,
    keys: &[&str],
    ours: impl Fn(&Value) -> bool,
) -> Result<(String, usize)> {
    let doc = parse_at(raw, path)?;
    let found = keys.iter().try_fold(&doc, |v, k| v.get(*k));
    let hits: Vec<usize> = found
        .and_then(Value::as_array)
        .map(|a| (0..a.len()).filter(|&i| ours(&a[i])).rev().collect())
        .unwrap_or_default();
    let mut text = raw.to_string();
    let mut segs: Vec<Seg> = keys.iter().map(|k| Seg::Key(k)).collect();
    for &i in &hits {
        segs.push(Seg::Index(i));
        text = remove_at(&text, path, &segs)?.0;
        segs.pop();
    }
    while !hits.is_empty() && !segs.is_empty() && is_empty_at(&text, &segs) {
        text = remove_at(&text, path, &segs)?.0;
        segs.pop();
    }
    Ok((text, hits.len()))
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn entry() -> Value {
        json!({"command": "rtok", "args": ["mcp"]})
    }

    /// jsonc-parser 0.34 can parse JSON5. Those flags stay off: comments and trailing
    /// commas are the only looseness Zed and VS Code settings get.
    #[test]
    fn parse_rejects_json5_numbers_quotes_and_escapes() {
        assert!(parse("{ /* c */ \"a\": 1, }").is_ok());
        for raw in [".5", "Infinity", "NaN", "+1", "{'a': 1}", "\"\\x41\""] {
            assert!(parse(raw).is_err(), "{raw} must stay rejected");
        }
    }

    /// A block comment holding non-ASCII text (`/* café */`) used to panic: the comment
    /// scanners stepped one byte at a time and sliced `text[i..]` inside a multi-byte char.
    #[test]
    fn block_comment_with_non_ascii_text_does_not_panic() {
        let raw = "{\n  /* café — ünïcode */\n  \"theme\": \"dark\"\n}\n";
        let (body, edit) =
            upsert_member(raw, Path::new("t"), "context_servers", "rtok", &entry()).unwrap();
        assert_eq!(edit, Upsert::Added, "{body}");
        assert!(body.contains("/* café — ünïcode */"), "{body}");
        assert_eq!(parse(&body).unwrap()["context_servers"]["rtok"], entry());
        let (back, removed) =
            remove_member(&body, Path::new("t"), "context_servers", "rtok").unwrap();
        assert!(removed, "{back}");
        assert_eq!(strip_comments(raw), "{\n  \n  \"theme\": \"dark\"\n}\n");
    }

    /// T328: replacing a literal entry value must keep the comment that follows it, even when a
    /// comma or newline sits between the literal and the next member.
    #[test]
    fn replacing_a_literal_entry_keeps_the_trailing_comment() {
        let entry = entry();
        let want_value = render_entry(&entry, 4);
        for (before, after) in [
            ("true // note\n,", "// note\n,"),
            ("true // a, b\n,", "// a, b\n,"),
            ("true /* keep */ ,", "/* keep */ ,"),
        ] {
            let raw = format!("{{\"s\": {{\"rtok\": {before}\"x\": 1}}}}");
            let (body, edit) = upsert_member(&raw, Path::new("t"), "s", "rtok", &entry).unwrap();
            assert_eq!(edit, Upsert::Replaced, "{body}");
            let want = format!("{{\"s\": {{\"rtok\": {want_value} {after}\"x\": 1}}}}");
            assert_eq!(body, want);
        }
    }

    /// T328: a trailing comment after an object-valued entry is untouched by a replace.
    #[test]
    fn replacing_an_object_entry_keeps_the_trailing_comment() {
        let raw = "{\"s\": {\"rtok\": {\"command\": \"old\"} /* keep */ , \"x\": 1}}";
        let (body, edit) = upsert_member(raw, Path::new("t"), "s", "rtok", &entry()).unwrap();
        assert_eq!(edit, Upsert::Replaced, "{body}");
        let want = format!(
            "{{\"s\": {{\"rtok\": {} /* keep */ , \"x\": 1}}}}",
            render_entry(&entry(), 4)
        );
        assert_eq!(body, want);
    }

    /// White-box: the scanner finds keys through strings, comments and nesting, and an
    /// insert against our own pretty-printed entry (comments and all) is idempotent.
    #[test]
    fn scanner_finds_keys_through_strings_comments_and_nesting() {
        let raw = "{\n  // \"context_servers\": fake\n  \"url\": \"https://x/{\\\"a\\\"}\",\n  /* multi\n  \"rtok\": 1 */\n  \"context_servers\": {\"other\": [1, {\"rtok\": 2}], \"rtok\": {\"command\": \"rtok\", \"args\": [\"mcp\"]}}\n}\n";
        assert_eq!(parse(raw).unwrap()["context_servers"]["rtok"], entry());
        let (body, edit) =
            upsert_member(raw, Path::new("t"), "context_servers", "rtok", &entry()).unwrap();
        assert_eq!(edit, Upsert::NoChange, "{body}");
    }

    #[test]
    fn adds_replaces_and_is_idempotent_on_a_missing_top_key() {
        let (body, edit) = upsert_member(
            "",
            Path::new("t"),
            "chat.pluginLocations",
            "/p",
            &json!(true),
        )
        .unwrap();
        assert_eq!(edit, Upsert::Added);
        let root = parse(&body).unwrap();
        assert_eq!(root["chat.pluginLocations"]["/p"], json!(true));

        let (body2, edit2) = upsert_member(
            &body,
            Path::new("t"),
            "chat.pluginLocations",
            "/p",
            &json!(true),
        )
        .unwrap();
        assert_eq!(edit2, Upsert::NoChange);

        let (body3, edit3) = upsert_member(
            &body2,
            Path::new("t"),
            "chat.pluginLocations",
            "/p",
            &json!(false),
        )
        .unwrap();
        assert_eq!(edit3, Upsert::Replaced);
        assert_eq!(
            parse(&body3).unwrap()["chat.pluginLocations"]["/p"],
            json!(false)
        );
    }

    /// Keys that need JSON escaping (a Windows-shaped path) must round-trip, not corrupt
    /// the document (real risk once the entry key is a filesystem path, not a fixed name).
    #[test]
    fn entry_keys_needing_escapes_round_trip() {
        let key = r#"C:\Users\a "quoted"\plugins\rtok"#;
        let (body, edit) = upsert_member(
            "{}",
            Path::new("t"),
            "chat.pluginLocations",
            key,
            &json!(true),
        )
        .unwrap();
        assert_eq!(edit, Upsert::Added);
        let root = parse(&body).unwrap();
        assert_eq!(root["chat.pluginLocations"][key], json!(true));
        let (body2, removed) =
            remove_member(&body, Path::new("t"), "chat.pluginLocations", key).unwrap();
        assert!(removed);
        assert!(
            parse(&body2).unwrap()["chat.pluginLocations"]
                .get(key)
                .is_none()
        );
    }

    #[test]
    fn remove_keeps_foreign_members_and_comments() {
        let raw = "{\n  // mine\n  \"chat.pluginLocations\": {\n    // foreign\n    \"/other\": true,\n    \"/p\": true\n  }\n}\n";
        let (body, removed) =
            remove_member(raw, Path::new("t"), "chat.pluginLocations", "/p").unwrap();
        assert!(removed);
        assert!(body.contains("// mine"));
        assert!(body.contains("// foreign"));
        assert!(body.contains("\"/other\""));
        assert!(!body.contains("\"/p\""));
        let root = parse(&body).unwrap();
        assert_eq!(root["chat.pluginLocations"]["/other"], json!(true));
    }

    #[test]
    fn remove_on_missing_entry_is_a_no_op() {
        let (body, removed) =
            remove_member("{}", Path::new("t"), "chat.pluginLocations", "/p").unwrap();
        assert!(!removed);
        assert_eq!(body, "{}");
    }

    fn push(raw: &str, keys: &[&str], item: Value) -> String {
        push_item(raw, Path::new("t"), keys, &item).unwrap()
    }

    fn pull(raw: &str, keys: &[&str]) -> (String, usize) {
        pull_items(raw, Path::new("t"), keys, |v| {
            v == "ours" || v["c"] == "ours"
        })
        .unwrap()
    }

    /// Every other byte stays whatever the file's style: inline list, one element per line, a
    /// trailing comma, a comment, an empty list, and a file that is blank or only `{}`.
    #[test]
    fn push_item_follows_the_list_style_and_pull_items_undoes_it() {
        let ours = json!("ours");
        let hook = json!({"c": "ours"});
        for (raw, keys, item, exact) in [
            (
                "{\n  // c\n  \"a\": [\"x\", \"y\"],\n  \"b\": 1\n}\n",
                &["a"][..],
                &ours,
                true,
            ),
            (
                "{\n  \"a\": [\n    \"x\",\n    \"y\",\n  ]\n}\n",
                &["a"][..],
                &ours,
                true,
            ),
            (
                "{\n  \"h\": {\n    \"o\": [1]\n  }\n}\n",
                &["h", "p"][..],
                &hook,
                true,
            ),
            (
                "{\n  \"h\": {\n    \"p\": [\n      {\"c\": \"x\"}\n    ]\n  }\n}\n",
                &["h", "p"][..],
                &hook,
                true,
            ),
            ("{\n  // only a comment\n}\n", &["h", "p"][..], &hook, false),
            ("{\"z\": 1}", &["h", "p"][..], &hook, false),
        ] {
            let got = push(raw, keys, item.clone());
            let doc = parse(&got).unwrap();
            let list = keys.iter().fold(&doc, |v, k| &v[*k]).as_array().unwrap();
            assert!(
                list.iter().any(|v| v == "ours" || v["c"] == "ours"),
                "{got}"
            );
            let (back, n) = pull(&got, keys);
            assert_eq!(n, 1, "{got}");
            if exact {
                assert_eq!(back, raw, "{got}");
            } else {
                assert_eq!(
                    parse(&back).unwrap(),
                    parse(raw).unwrap(),
                    "{raw} -> {got} -> {back}"
                );
            }
        }
    }

    #[test]
    fn push_item_creates_a_blank_file_and_refuses_a_value_of_another_shape() {
        assert_eq!(
            parse(&push("{\"a\": []}", &["a"], json!("ours"))).unwrap(),
            json!({"a": ["ours"]})
        );
        let got = push("", &["h", "p"], json!("ours"));
        assert_eq!(parse(&got).unwrap(), json!({"h": {"p": ["ours"]}}));
        let (back, n) = pull(&got, &["h", "p"]);
        assert_eq!(n, 1);
        assert!(is_empty_at(&back, &[]), "{back:?}");
        for raw in ["{\"h\": 1}", "{\"h\": {\"p\": {}}}", "[1]"] {
            assert!(
                push_item(raw, Path::new("t"), &["h", "p"], &json!("x")).is_err(),
                "{raw}"
            );
        }
    }

    #[test]
    fn pull_items_leaves_a_list_that_holds_nothing_of_ours() {
        let raw = "{\"a\": [\"x\"]}";
        assert_eq!(pull(raw, &["a"]), (raw.to_string(), 0));
        assert_eq!(pull(raw, &["a", "b"]), (raw.to_string(), 0));
    }
}
