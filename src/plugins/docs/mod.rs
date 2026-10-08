// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! Local crate docs from `Cargo.lock` + a cached docs.rs rustdoc JSON index (T455).

use std::path::PathBuf;
use std::time::Duration;

use anyhow::{Context, Result};
use serde::Deserialize;
use serde_json::{Value, json};

use rtok_plugin_sdk::{Class, DashboardPage, Manifest, Measurement, Plugin, Surface, ToolDef};

pub struct Docs;

impl Plugin for Docs {
    fn manifest(&self) -> Manifest {
        Manifest {
            id: "docs",
            surfaces: &[Surface::Mcp, Surface::Cli],
            default_on: false,
        }
    }

    fn dashboard_page(&self) -> DashboardPage {
        DashboardPage::new(
            "Docs",
            "Ranked rustdoc snippets from Cargo.lock, fetched once and searched offline.",
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

#[derive(Debug, Deserialize)]
struct CargoLock {
    #[serde(default)]
    package: Vec<LockPackage>,
}

#[derive(Debug, Deserialize)]
struct LockPackage {
    name: String,
    version: String,
}

/// Exact name + version from a Cargo.lock body. First matching package wins.
pub fn resolve_lock(lock_text: &str, name: &str) -> Option<(String, String)> {
    let lock: CargoLock = toml::from_str(lock_text).ok()?;
    lock.package
        .into_iter()
        .find(|p| p.name == name)
        .map(|p| (p.name, p.version))
}

fn lock_path() -> PathBuf {
    std::env::current_dir()
        .unwrap_or_else(|_| PathBuf::from("."))
        .join("Cargo.lock")
}

fn miss_lock(name: &str) -> String {
    format!("not in Cargo.lock: {name}")
}

fn not_cached(name: &str) -> String {
    format!("not cached: rtok docs fetch {name}")
}

pub fn docs_resolve(rt: &crate::plugin::Runtime, name: &str) -> Result<String> {
    let _ = ingest_llms(rt);
    if name == "llms" {
        let cached = rt.store.doc_crate_cached("llms", "local").unwrap_or(false);
        return Ok(json!({"name":"llms","version":"local","cached":cached}).to_string());
    }
    let text = match std::fs::read_to_string(lock_path()) {
        Ok(t) => t,
        Err(_) => return Ok(miss_lock(name)),
    };
    let Some((n, ver)) = resolve_lock(&text, name) else {
        return Ok(miss_lock(name));
    };
    let cached = rt.store.doc_crate_cached(&n, &ver).unwrap_or(false);
    Ok(json!({"name":n,"version":ver,"cached":cached}).to_string())
}

pub fn docs_query(
    rt: &crate::plugin::Runtime,
    name: &str,
    version: Option<&str>,
    query: &str,
) -> Result<String> {
    let _ = ingest_llms(rt);
    let ver = match version {
        Some(v) if !v.is_empty() => v.to_string(),
        _ if name == "llms" => "local".into(),
        _ => {
            let text = match std::fs::read_to_string(lock_path()) {
                Ok(t) => t,
                Err(_) => return Ok(not_cached(name)),
            };
            match resolve_lock(&text, name) {
                Some((_, v)) => v,
                None => return Ok(not_cached(name)),
            }
        }
    };
    if !rt.store.doc_crate_cached(name, &ver).unwrap_or(false) {
        return Ok(not_cached(name));
    }
    let limit = rt.config.plugins.docs.query_limit.max(1);
    let snippet = rt.config.plugins.docs.snippet_chars.max(1);
    let max_tokens = rt.config.plugins.docs.max_tokens.max(1);
    let hits = rt
        .store
        .search_doc_items(name, &ver, query, limit, snippet)?;
    let considered: Vec<i32> = hits.iter().map(|h| h.id).collect();
    let before_bytes = rt.store.doc_items_docs_len(&considered).unwrap_or(0);
    let mut kept = Vec::new();
    for h in &hits {
        let next = json!({"id": h.id, "path": h.path, "kind": h.kind, "snippet": h.snippet});
        let mut trial = kept.clone();
        trial.push(next.clone());
        let text = serde_json::to_string(&trial).unwrap_or_default();
        if rt.estimate(&text, Class::Prose) > max_tokens {
            break;
        }
        kept.push(next);
    }
    let text = serde_json::to_string(&kept).unwrap_or_else(|_| "[]".into());
    if !kept.is_empty() {
        let after_bytes = text.len() as u64;
        let est_before = rt.estimate(
            &hits
                .iter()
                .map(|h| h.snippet.as_str())
                .collect::<Vec<_>>()
                .join("\n"),
            Class::Prose,
        );
        let est_after = rt.estimate(&text, Class::Prose);
        let _ = rt.record(&Measurement {
            plugin: "docs",
            kind: "query",
            before_bytes,
            after_bytes,
            est_before,
            est_after,
            ref_id: Some(format!("{name}@{ver}")),
            call_id: rt.call_id,
        });
    }
    Ok(text)
}

pub fn docs_get(rt: &crate::plugin::Runtime, id: i32) -> Result<String> {
    match rt.store.get_doc_item(id)? {
        Some(item) => Ok(json!({
            "id": item.id,
            "path": item.path,
            "kind": item.kind,
            "docs": item.docs,
        })
        .to_string()),
        None => anyhow::bail!("unknown doc id: {id}"),
    }
}

/// Split `llms.txt` / `llms-full.txt` on heading lines; crate `llms` version `local`.
fn ingest_llms(rt: &crate::plugin::Runtime) -> Result<()> {
    let cwd = std::env::current_dir().unwrap_or_else(|_| PathBuf::from("."));
    let path = ["llms-full.txt", "llms.txt"]
        .iter()
        .map(|n| cwd.join(n))
        .find(|p| p.is_file());
    let Some(path) = path else {
        return Ok(());
    };
    let body = std::fs::read_to_string(&path)?;
    let sha = crate::store::hex_sha256(body.as_bytes());
    let items = split_llms(&body);
    if items.is_empty() {
        return Ok(());
    }
    let now = i64::try_from(crate::log::now()).unwrap_or(0);
    rt.store
        .replace_doc_crate("llms", "local", &sha, 0, now, &items)?;
    Ok(())
}

fn split_llms(body: &str) -> Vec<(String, String, String)> {
    let mut items = Vec::new();
    let mut heading = String::from("llms");
    let mut buf = String::new();
    for line in body.lines() {
        if let Some(rest) = line.strip_prefix('#') {
            if !buf.trim().is_empty() {
                items.push((heading.clone(), "llms".into(), buf.trim().to_string()));
            }
            heading = rest.trim().to_string();
            if heading.is_empty() {
                heading = "llms".into();
            }
            buf.clear();
        } else {
            buf.push_str(line);
            buf.push('\n');
        }
    }
    if !buf.trim().is_empty() {
        items.push((heading, "llms".into(), buf.trim().to_string()));
    }
    items
}

/// Walk rustdoc JSON `paths` + `index`; keep path, kind, docs string.
pub fn index_rustdoc(json: &Value) -> (i32, Vec<(String, String, String)>) {
    let format_version = json
        .get("format_version")
        .and_then(Value::as_u64)
        .unwrap_or(0) as i32;
    let paths = json.get("paths").and_then(Value::as_object);
    let index = json.get("index").and_then(Value::as_object);
    let mut items = Vec::new();
    if let Some(paths) = paths {
        for (id, summary) in paths {
            let path = summary
                .get("path")
                .and_then(Value::as_array)
                .map(|a| {
                    a.iter()
                        .filter_map(Value::as_str)
                        .collect::<Vec<_>>()
                        .join("::")
                })
                .filter(|s| !s.is_empty())
                .unwrap_or_else(|| id.clone());
            let kind = kind_str(summary.get("kind").unwrap_or(&Value::Null));
            let docs = index
                .and_then(|ix| ix.get(id))
                .and_then(|item| item.get("docs"))
                .and_then(Value::as_str)
                .unwrap_or("")
                .to_string();
            if docs.is_empty() {
                continue;
            }
            items.push((path, kind, docs));
        }
    }
    (format_version, items)
}

fn kind_str(v: &Value) -> String {
    if let Some(s) = v.as_str() {
        return s.to_string();
    }
    if let Some(obj) = v.as_object()
        && let Some(k) = obj.keys().next()
    {
        return k.clone();
    }
    "item".into()
}

pub fn fetch_crate(
    rt: &crate::plugin::Runtime,
    name: &str,
    get: impl Fn(&str) -> Result<(u16, Vec<u8>)>,
) -> Result<String> {
    let _ = ingest_llms(rt);
    let text = std::fs::read_to_string(lock_path()).unwrap_or_default();
    let Some((n, ver)) = resolve_lock(&text, name) else {
        return Ok(miss_lock(name));
    };
    let url = format!("https://docs.rs/crate/{n}/{ver}/json.zst");
    let (status, bytes) = get(&url)?;
    if status != 200 {
        return Ok(format!("docs.rs returned {status}"));
    }
    let sha = crate::store::hex_sha256(&bytes);
    let raw = zstd::decode_all(bytes.as_slice()).context("zstd decode of rustdoc JSON")?;
    let json: Value = serde_json::from_slice(&raw).context("rustdoc JSON")?;
    let (format_version, items) = index_rustdoc(&json);
    let dir = rt.config.home.join("docs").join(&n).join(&ver);
    std::fs::create_dir_all(&dir)?;
    let zst_path = dir.join("rustdoc.json.zst");
    let sha_path = dir.join("rustdoc.json.zst.sha256");
    std::fs::write(&zst_path, &bytes)?;
    std::fs::write(&sha_path, format!("{sha}\n"))?;
    let now = i64::try_from(crate::log::now()).unwrap_or(0);
    let n_items = rt
        .store
        .replace_doc_crate(&n, &ver, &sha, format_version, now, &items)?;
    Ok(format!("cached {n} {ver} ({n_items} items)"))
}

pub fn http_get(url: &str) -> Result<(u16, Vec<u8>)> {
    let client = reqwest::blocking::Client::builder()
        .user_agent(format!("rtok/{}", env!("CARGO_PKG_VERSION")))
        .timeout(Duration::from_secs(30))
        .build()
        .context("docs fetch client")?;
    let resp = client.get(url).send().context("docs.rs GET")?;
    let status = resp.status().as_u16();
    let bytes = resp.bytes().map(|b| b.to_vec()).unwrap_or_default();
    Ok((status, bytes))
}

pub fn run_fetch(cfg: &crate::config::Config, name: &str) -> Result<String> {
    let rt = crate::plugin::Runtime::open(cfg.clone(), "docs")?;
    fetch_crate(&rt, name, http_get)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::plugin::Runtime;
    use std::sync::Mutex;

    const LOCK: &str = r#"
version = 3

[[package]]
name = "serde"
version = "1.0.210"
source = "registry+https://github.com/rust-lang/crates.io-index"
checksum = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
"#;

    static CWD: Mutex<()> = Mutex::new(());

    fn runtime(tag: &str) -> Runtime {
        let mut rt = Runtime::in_memory(tag).unwrap();
        rt.config.plugins.docs.enabled = true;
        rt
    }

    fn with_lock_cwd<T>(lock_text: &str, f: impl FnOnce() -> T) -> T {
        let _guard = CWD.lock().unwrap();
        let dir = std::env::temp_dir().join(format!(
            "rtok-docs-lock-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        let _ = std::fs::create_dir_all(&dir);
        std::fs::write(dir.join("Cargo.lock"), lock_text).unwrap();
        let prev = std::env::current_dir().unwrap();
        std::env::set_current_dir(&dir).unwrap();
        struct Restore(PathBuf);
        impl Drop for Restore {
            fn drop(&mut self) {
                let _ = std::env::set_current_dir(&self.0);
            }
        }
        let _restore = Restore(prev);
        let out = f();
        let _ = std::fs::remove_dir_all(&dir);
        out
    }

    #[test]
    fn resolve_lock_prints_serde_version() {
        let (name, ver) = resolve_lock(LOCK, "serde").expect("serde in lock");
        assert_eq!((name.as_str(), ver.as_str()), ("serde", "1.0.210"));
        assert!(resolve_lock(LOCK, "nope").is_none());
    }

    #[test]
    fn docs_resolve_prints_serde_version_from_fixture_lock() {
        let rt = runtime("docs-lock");
        let out = with_lock_cwd(LOCK, || docs_resolve(&rt, "serde").unwrap());
        let v: Value = serde_json::from_str(&out).unwrap();
        assert_eq!(v["name"], "serde");
        assert_eq!(v["version"], "1.0.210");
        assert_eq!(v["cached"], false);
    }

    #[test]
    fn docs_resolve_unknown_name_is_a_miss_line() {
        let rt = runtime("docs-miss");
        let out = with_lock_cwd(LOCK, || {
            docs_resolve(&rt, "definitely-missing-crate").unwrap()
        });
        assert!(out.starts_with("not in Cargo.lock:"), "{out}");
    }

    #[test]
    fn query_deserialize_ranks_matching_path_first_under_budget() {
        let rt = runtime("docs-q");
        rt.store
            .replace_doc_crate(
                "serde",
                "1.0.210",
                "sha",
                1,
                0,
                &[
                    (
                        "serde::Serialize".into(),
                        "trait".into(),
                        "Serialize a data structure from Rust to a data format.".into(),
                    ),
                    (
                        "serde::Deserialize".into(),
                        "trait".into(),
                        "Deserialize a data structure from a data format into Rust.".into(),
                    ),
                ],
            )
            .unwrap();
        let mut rt = rt;
        rt.config.plugins.docs.max_tokens = 800;
        let out = docs_query(&rt, "serde", Some("1.0.210"), "Deserialize").unwrap();
        let v: Value = serde_json::from_str(&out).unwrap();
        let arr = v.as_array().expect(&out);
        assert!(!arr.is_empty(), "{out}");
        let path = arr[0]["path"].as_str().unwrap_or("");
        assert!(
            path.contains("Deserialize"),
            "first hit should be Deserialize: {out}"
        );
        let est = rt.estimate(&out, Class::Prose);
        assert!(est <= 800, "reply {est} tokens: {out}");
        let rows = rt.store.list_measurements("docs").unwrap();
        assert!(
            rows.iter().any(|r| r.kind == "query"),
            "query records a Measurement"
        );
    }

    #[test]
    fn query_uncached_prints_not_cached_without_http() {
        let rt = runtime("docs-empty");
        let out = docs_query(&rt, "serde", Some("1.0.210"), "Deserialize").unwrap();
        assert_eq!(out, "not cached: rtok docs fetch serde");
    }

    #[test]
    fn mcp_surface_stays_within_sixty_description_tokens() {
        let cx = Runtime::in_memory("docs-surface").unwrap();
        let total: i64 = Docs
            .mcp_tools()
            .iter()
            .map(|t| i64::from(cx.estimate(t.description, Class::Prose)))
            .sum();
        assert!(total <= 60, "docs tool surface is {total} tokens");
    }

    #[test]
    fn fetch_non_200_leaves_cache_untouched() {
        let rt = runtime("docs-404");
        rt.store
            .replace_doc_crate(
                "serde",
                "1.0.210",
                "old",
                1,
                0,
                &[("serde::Keep".into(), "struct".into(), "stay".into())],
            )
            .unwrap();
        let out = with_lock_cwd(LOCK, || {
            fetch_crate(&rt, "serde", |_| Ok((404, b"nope".to_vec()))).unwrap()
        });
        assert!(out.contains("404"), "{out}");
        let hits = rt
            .store
            .search_doc_items("serde", "1.0.210", "stay", 5, 40)
            .unwrap();
        assert_eq!(hits.len(), 1, "old cache kept");
    }

    #[test]
    fn llms_txt_splits_on_hash_headings() {
        let sections = split_llms("# One\nbody a\n# Two\nbody b\n");
        assert_eq!(sections.len(), 2);
        assert_eq!(sections[0].0, "One");
        assert_eq!(sections[0].2, "body a");
        assert_eq!(sections[1].0, "Two");
    }
}
