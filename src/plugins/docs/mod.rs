// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! Local dependency docs (T471). Versions come from `Cargo.lock`. Bodies come from a
//! cache of docs.rs rustdoc JSON. Nothing here calls context7.com or sends an API key.

use std::io::{Read, Write};
use std::path::{Path, PathBuf};

use anyhow::{Context, Result, bail};
use rtok_plugin_sdk::{Class, Ctx, DashboardPage, Manifest, Measurement, Plugin, Surface, ToolDef};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};

use crate::plugin::Runtime;
use crate::store::DocHit;

pub struct Docs;

impl Plugin for Docs {
    fn manifest(&self) -> Manifest {
        Manifest {
            id: "docs",
            surfaces: &[Surface::Mcp],
            default_on: false,
        }
    }

    fn dashboard_page(&self) -> DashboardPage {
        DashboardPage::new(
            "Docs",
            "Exact dependency versions from Cargo.lock, snippets from a local rustdoc cache.",
            true,
        )
    }

    fn mcp_tools(&self) -> Vec<ToolDef> {
        vec![
            ToolDef {
                name: "docs_resolve",
                description: "Exact crate name and version from Cargo.lock for a dependency name.",
                input_schema: json!({"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}),
            },
            ToolDef {
                name: "docs_query",
                description: "FTS snippets for one cached crate version; ids only, docs_get loads a body.",
                input_schema: json!({"type":"object","properties":{"name":{"type":"string"},"version":{"type":"string"},"query":{"type":"string"}},"required":["name","query"]}),
            },
            ToolDef {
                name: "docs_get",
                description: "One cached doc item by id.",
                input_schema: json!({"type":"object","properties":{"id":{"type":"integer"}},"required":["id"]}),
            },
        ]
    }
}

/// `docs_query` accepts the keys models invent. The advertised schema stays `query`.
pub fn alias_query_args(args: &mut Value) {
    let Some(obj) = args.as_object_mut() else {
        return;
    };
    if obj.contains_key("query") {
        return;
    }
    if let Some(q) = obj.remove("question").or_else(|| obj.remove("userQuery")) {
        obj.insert("query".into(), q);
    }
}

pub fn docs_resolve(rt: &Runtime, name: &str) -> Result<String> {
    docs_resolve_from(rt, name, &std::env::current_dir()?)
}

fn docs_resolve_from(rt: &Runtime, name: &str, start: &Path) -> Result<String> {
    let name = name.trim();
    if name.is_empty() {
        bail!("missing crate name");
    }
    if name.eq_ignore_ascii_case("llms") {
        let cached = rt.store.doc_cached("llms", "local")?;
        return Ok(format!("llms local cached:{cached}"));
    }
    let text = read_lock(start)?;
    let versions = lock_versions(&text, name);
    if versions.is_empty() {
        return Ok(format!("no crate {name} in Cargo.lock"));
    }
    let mut lines = Vec::new();
    for version in versions {
        let cached = rt.store.doc_cached(name, &version)?;
        lines.push(format!("{name} {version} cached:{cached}"));
    }
    Ok(lines.join("\n"))
}

pub fn docs_query(rt: &Runtime, name: &str, version: Option<&str>, query: &str) -> Result<String> {
    let name = name.trim();
    if name.is_empty() {
        bail!("missing crate name");
    }
    if name.eq_ignore_ascii_case("llms")
        && let Ok(cwd) = std::env::current_dir()
    {
        index_llms_dir(&rt.store, &cwd)?;
    }
    let version = resolve_version(rt, name, version)?;
    let Some(version) = version else {
        return Ok(if name.eq_ignore_ascii_case("llms") {
            "not cached: add llms.txt to the workspace".to_string()
        } else {
            format!("not cached: rtok docs fetch {name}")
        });
    };
    if !rt.store.doc_cached(name, &version)? {
        return Ok(format!("not cached: rtok docs fetch {name}"));
    }
    let cfg = &rt.config.plugins.docs;
    let hits = rt
        .store
        .search_docs(name, &version, query, cfg.snippet_chars, cfg.query_limit)?;
    if hits.is_empty() {
        return Ok(format!("no docs for {query}"));
    }
    let before_bytes: u64 = hits.iter().map(|h| h.docs.len() as u64).sum();
    let full = hits
        .iter()
        .map(|h| format!("{} {} {}\n{}", h.id, h.path, h.kind, h.docs))
        .collect::<Vec<_>>()
        .join("\n");
    let cx = Ctx::new(rt);
    let text = fit_hits(&cx, &hits, cfg.max_tokens);
    let _ = cx.record(&Measurement {
        plugin: "docs",
        kind: "query",
        before_bytes,
        after_bytes: text.len() as u64,
        est_before: cx.estimate(&full, Class::Prose),
        est_after: cx.estimate(&text, Class::Prose),
        ref_id: None,
        call_id: None,
    });
    Ok(text)
}

pub fn docs_get(rt: &Runtime, id: i32) -> Result<String> {
    match rt.store.get_doc(id)? {
        Some((path, kind, docs)) => Ok(format!("{path} {kind}\n{docs}")),
        None => Ok(format!("unknown doc id: {id}")),
    }
}

/// Download one crate's rustdoc JSON. `download` is the only network step.
pub fn fetch_with(
    rt: &Runtime,
    home: &Path,
    start: &Path,
    name: &str,
    version: Option<&str>,
    download: impl Fn(&str) -> Result<Vec<u8>>,
) -> Result<String> {
    let name = name.trim();
    let text = read_lock(start)?;
    let versions = lock_versions(&text, name);
    let version = match version {
        Some(v) => v.to_string(),
        None if versions.len() == 1 => versions[0].clone(),
        None if versions.is_empty() => bail!("no crate {name} in Cargo.lock"),
        None => bail!(
            "several versions of {name}: {} — pass version",
            versions.join(", ")
        ),
    };
    let url = format!("https://docs.rs/crate/{name}/{version}/json.gz");
    let gz = download(&url).with_context(|| format!("fetch {url}"))?;
    let json_bytes = gunzip(&gz).context("docs.rs body was not gzip")?;
    let value: Value = serde_json::from_slice(&json_bytes).context("docs.rs body was not JSON")?;
    let (format_version, items) = items_from_rustdoc(&value);
    let sha = hex_sha256(&gz);
    write_cache(home, name, &version, &gz, &sha)?;
    let n = rt.store.replace_docs(
        name,
        &version,
        &sha,
        format_version,
        i64::try_from(crate::log::now()).unwrap_or(i64::MAX),
        &items,
    )?;
    Ok(format!("cached {name} {version} ({n} items)"))
}

pub fn download_rustdoc(url: &str) -> Result<Vec<u8>> {
    let client = reqwest::blocking::Client::builder()
        .use_preconfigured_tls(crate::tls::preconfigured()?)
        .user_agent(concat!("rtok/", env!("CARGO_PKG_VERSION")))
        .timeout(std::time::Duration::from_secs(30))
        .build()?;
    let response = client.get(url).send()?;
    let status = response.status();
    if !status.is_success() {
        bail!("docs.rs status {status}");
    }
    Ok(response.bytes()?.to_vec())
}

/// Versions of `name` in a `Cargo.lock` body, sorted and de-duplicated.
pub fn lock_versions(text: &str, name: &str) -> Vec<String> {
    let Ok(doc) = toml_edit::Document::parse(text.to_owned()) else {
        return Vec::new();
    };
    let Some(packages) = doc
        .get("package")
        .and_then(|item| item.as_array_of_tables())
    else {
        return Vec::new();
    };
    let mut versions = Vec::new();
    for pkg in packages {
        let Some(pkg_name) = pkg.get("name").and_then(|item| item.as_str()) else {
            continue;
        };
        if !pkg_name.eq_ignore_ascii_case(name) {
            continue;
        }
        if let Some(version) = pkg.get("version").and_then(|item| item.as_str()) {
            versions.push(version.to_string());
        }
    }
    versions.sort();
    versions.dedup();
    versions
}

/// `(format_version, path, kind, docs)` from a rustdoc JSON object. Empty docs are dropped.
pub fn items_from_rustdoc(json: &Value) -> (i32, Vec<(String, String, String)>) {
    let format_version = json
        .get("format_version")
        .and_then(Value::as_i64)
        .and_then(|n| i32::try_from(n).ok())
        .unwrap_or(0);
    let paths = json.get("paths").and_then(Value::as_object);
    let Some(index) = json.get("index").and_then(Value::as_object) else {
        return (format_version, Vec::new());
    };
    let mut items = Vec::new();
    for (id, item) in index {
        let docs = item
            .get("docs")
            .and_then(Value::as_str)
            .unwrap_or("")
            .trim();
        if docs.is_empty() {
            continue;
        }
        let (path, kind) = match paths.and_then(|p| p.get(id)) {
            Some(entry) => {
                let path = entry
                    .get("path")
                    .and_then(Value::as_array)
                    .map(|parts| {
                        parts
                            .iter()
                            .filter_map(Value::as_str)
                            .collect::<Vec<_>>()
                            .join("::")
                    })
                    .filter(|p| !p.is_empty())
                    .unwrap_or_else(|| id.clone());
                let kind = entry
                    .get("kind")
                    .and_then(Value::as_str)
                    .unwrap_or("item")
                    .to_string();
                (path, kind)
            }
            None => (id.clone(), "item".to_string()),
        };
        items.push((path, kind, docs.to_string()));
    }
    items.sort_by(|a, b| a.0.cmp(&b.0));
    (format_version, items)
}

/// Heading sections of an `llms.txt` body: `(title, text)`.
pub fn llms_sections(text: &str) -> Vec<(String, String)> {
    let mut title = "llms".to_string();
    let mut body = String::new();
    let mut out = Vec::new();
    let flush = |title: &str, body: &str, out: &mut Vec<(String, String)>| {
        let body = body.trim();
        if !body.is_empty() {
            let title = if title.is_empty() { "section" } else { title };
            out.push((title.to_string(), body.to_string()));
        }
    };
    for line in text.lines() {
        if let Some(rest) = line.strip_prefix('#') {
            flush(&title, &body, &mut out);
            title = rest.trim().trim_start_matches('#').trim().to_string();
            body.clear();
        } else {
            body.push_str(line);
            body.push('\n');
        }
    }
    flush(&title, &body, &mut out);
    out
}

fn index_llms_dir(store: &crate::store::Store, dir: &Path) -> Result<()> {
    let mut chunks = Vec::new();
    for file in ["llms.txt", "llms-full.txt"] {
        let path = dir.join(file);
        if !path.is_file() {
            continue;
        }
        let text = std::fs::read_to_string(&path).unwrap_or_default();
        for (title, body) in llms_sections(&text) {
            chunks.push((format!("{file}#{title}"), "llms".to_string(), body));
        }
    }
    if chunks.is_empty() {
        return Ok(());
    }
    let raw = chunks
        .iter()
        .map(|(_, _, body)| body.as_str())
        .collect::<Vec<_>>()
        .join("\n");
    store.replace_docs(
        "llms",
        "local",
        &hex_sha256(raw.as_bytes()),
        0,
        i64::try_from(crate::log::now()).unwrap_or(i64::MAX),
        &chunks,
    )?;
    Ok(())
}

fn resolve_version(rt: &Runtime, name: &str, version: Option<&str>) -> Result<Option<String>> {
    if name.eq_ignore_ascii_case("llms") {
        return Ok(rt
            .store
            .doc_cached("llms", "local")?
            .then(|| "local".to_string()));
    }
    if let Some(version) = version.map(str::trim).filter(|v| !v.is_empty()) {
        return Ok(Some(version.to_string()));
    }
    let text = std::env::current_dir()
        .ok()
        .and_then(|dir| read_lock(&dir).ok())
        .unwrap_or_default();
    let versions = lock_versions(&text, name);
    match versions.len() {
        0 => Ok(None),
        1 => Ok(Some(versions.into_iter().next().unwrap())),
        _ => bail!(
            "several versions of {name}: {} — pass version",
            versions.join(", ")
        ),
    }
}

fn fit_hits(cx: &Ctx, hits: &[DocHit], max_tokens: u32) -> String {
    let mut kept: Vec<String> = Vec::new();
    for hit in hits {
        let mut snippet = hit.snippet.clone();
        loop {
            let line = format!("{} {} {}\n{snippet}", hit.id, hit.path, hit.kind);
            let mut candidate = kept.clone();
            candidate.push(line.clone());
            let text = candidate.join("\n");
            if cx.estimate(&text, Class::Prose) <= max_tokens.max(1) {
                kept.push(line);
                break;
            }
            if snippet.is_empty() {
                if kept.is_empty() {
                    return format!("{} {} {}", hit.id, hit.path, hit.kind);
                }
                return kept.join("\n");
            }
            let take = snippet.chars().count() / 2;
            snippet = snippet.chars().take(take).collect();
        }
    }
    kept.join("\n")
}

fn read_lock(start: &Path) -> Result<String> {
    let Some(path) = find_lock(start) else {
        bail!("no Cargo.lock from {}", start.display());
    };
    Ok(std::fs::read_to_string(path)?)
}

fn find_lock(start: &Path) -> Option<PathBuf> {
    let mut dir = Some(start);
    while let Some(current) = dir {
        let candidate = current.join("Cargo.lock");
        if candidate.is_file() {
            return Some(candidate);
        }
        dir = current.parent();
    }
    None
}

fn write_cache(home: &Path, name: &str, version: &str, gz: &[u8], sha: &str) -> Result<()> {
    let dir = home.join("docs").join(name).join(version);
    std::fs::create_dir_all(&dir)?;
    let tmp = dir.join("rustdoc.json.gz.partial");
    {
        let mut file = std::fs::File::create(&tmp)?;
        file.write_all(gz)?;
    }
    std::fs::rename(&tmp, dir.join("rustdoc.json.gz"))?;
    std::fs::write(dir.join("sha256"), format!("{sha}\n"))?;
    Ok(())
}

fn gunzip(bytes: &[u8]) -> Result<Vec<u8>> {
    let mut decoder = flate2::read::GzDecoder::new(bytes);
    let mut out = Vec::new();
    decoder.read_to_end(&mut out)?;
    Ok(out)
}

fn hex_sha256(bytes: &[u8]) -> String {
    let mut hasher = Sha256::new();
    hasher.update(bytes);
    hasher
        .finalize()
        .iter()
        .map(|b| format!("{b:02x}"))
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use rtok_plugin_sdk::Ctx;

    const LOCK: &str = r#"
version = 3

[[package]]
name = "serde"
version = "1.0.210"

[[package]]
name = "other"
version = "0.1.0"
"#;

    #[test]
    fn lock_versions_reads_the_exact_serde_version() {
        assert_eq!(lock_versions(LOCK, "serde"), vec!["1.0.210".to_string()]);
        assert!(lock_versions(LOCK, "missing").is_empty());
    }

    #[test]
    fn resolve_prints_the_lockfile_version() {
        let dir = std::env::temp_dir().join(format!("rtok-docs-lock-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(dir.join("Cargo.lock"), LOCK).unwrap();
        let cx = Runtime::in_memory("t471-resolve").unwrap();
        let text = docs_resolve_from(&cx, "serde", &dir).unwrap();
        let _ = std::fs::remove_dir_all(&dir);
        assert_eq!(text, "serde 1.0.210 cached:false");
    }

    #[test]
    fn query_ranks_deserialize_and_stays_under_the_budget() {
        let cx = Runtime::in_memory("t471-query").unwrap();
        cx.store
            .replace_docs(
                "serde",
                "1.0.210",
                "abc",
                1,
                0,
                &[
                    (
                        "serde::Deserialize".into(),
                        "trait".into(),
                        "Implement Deserialize for a type".into(),
                    ),
                    (
                        "serde::Serialize".into(),
                        "trait".into(),
                        "Implement Serialize for a type".into(),
                    ),
                ],
            )
            .unwrap();
        let text = docs_query(&cx, "serde", Some("1.0.210"), "Deserialize").unwrap();
        assert!(text.contains("serde::Deserialize"), "{text}");
        assert!(text.lines().next().unwrap().contains("Deserialize"));
        let est = Ctx::new(&cx).estimate(&text, Class::Prose);
        assert!(est <= cx.config.plugins.docs.max_tokens, "{est} {text}");
        let rows = cx.store.list_measurements("docs").unwrap();
        assert_eq!(rows.len(), 1);
        assert_eq!(rows[0].kind, "query");
        assert!(rows[0].before_bytes > 0);
    }

    #[test]
    fn missing_cache_names_fetch_and_does_not_download() {
        let cx = Runtime::in_memory("t471-miss").unwrap();
        let text = docs_query(&cx, "serde", Some("1.0.210"), "Deserialize").unwrap();
        assert_eq!(text, "not cached: rtok docs fetch serde");
        assert!(cx.store.list_measurements("docs").unwrap().is_empty());
    }

    #[test]
    fn descriptions_stay_within_sixty_tokens() {
        let cx = Runtime::in_memory("t471-surface").unwrap();
        let total: u32 = Docs
            .mcp_tools()
            .iter()
            .map(|tool| Ctx::new(&cx).estimate(tool.description, Class::Prose))
            .sum();
        assert!(total <= 60, "docs tool surface is {total} tokens");
    }

    #[test]
    fn rustdoc_json_keeps_documented_items() {
        let json = json!({
            "format_version": 42,
            "paths": {"0": {"path": ["serde", "Deserialize"], "kind": "trait"}},
            "index": {
                "0": {"docs": "A trait."},
                "1": {"docs": "  "}
            }
        });
        let (version, items) = items_from_rustdoc(&json);
        assert_eq!(version, 42);
        assert_eq!(items.len(), 1);
        assert_eq!(items[0].0, "serde::Deserialize");
        assert_eq!(items[0].1, "trait");
    }

    #[test]
    fn llms_sections_split_on_headings() {
        let sections = llms_sections("# Crate\n\nHello\n\n## API\n\nuse it\n");
        assert_eq!(sections.len(), 2);
        assert_eq!(sections[0].0, "Crate");
        assert_eq!(sections[1].0, "API");
        assert!(sections[1].1.contains("use it"));
    }

    #[test]
    fn alias_renames_question_to_query() {
        let mut args = json!({"name": "serde", "question": "Deserialize"});
        alias_query_args(&mut args);
        assert_eq!(args["query"], "Deserialize");
        assert!(args.get("question").is_none());
    }

    #[test]
    fn fetch_with_stores_gzip_bytes_and_a_failed_download_keeps_the_old_cache() {
        let cx = Runtime::in_memory("t471-fetch").unwrap();
        let dir = std::env::temp_dir().join(format!("rtok-docs-fetch-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(dir.join("Cargo.lock"), LOCK).unwrap();
        let home = dir.join("home");
        let body = json!({
            "format_version": 1,
            "paths": {"0": {"path": ["serde", "Deserialize"], "kind": "trait"}},
            "index": {"0": {"docs": "A trait."}}
        })
        .to_string();
        let mut gz = Vec::new();
        {
            let mut enc = flate2::write::GzEncoder::new(&mut gz, flate2::Compression::default());
            enc.write_all(body.as_bytes()).unwrap();
            enc.finish().unwrap();
        }
        let line = fetch_with(&cx, &home, &dir, "serde", None, |_| Ok(gz.clone())).unwrap();
        assert!(line.contains("cached serde 1.0.210"), "{line}");
        assert!(cx.store.doc_cached("serde", "1.0.210").unwrap());
        let err = fetch_with(&cx, &home, &dir, "serde", None, |_| bail!("offline"));
        assert!(err.is_err());
        assert!(cx.store.doc_cached("serde", "1.0.210").unwrap());
        let _ = std::fs::remove_dir_all(&dir);
    }
}
