// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! T329.10: the last-resort graph backend. A project with no tree-sitter grammar and no language
//! server (or one pinned with `backend = "text"`) is answered by a word-boundary regex search
//! over its files, in process, with the walk rules of the `search` tool (T4.5; D6, D18): no
//! `rg`, `grep` or `ssh` is ever started, and `ssh://` roots are out of scope (ideas I-118).
//! Answers are best effort: a comment or a string that looks like a definition is a hit, and
//! there is no call graph, so `dead` and the `to` chains of `impact` are not offered.

use std::collections::{BTreeMap, HashMap};
use std::path::{Path, PathBuf};

use anyhow::{Result, bail};
use regex::Regex;

use rtok_plugin_sdk::Ctx;

use super::walk::Matcher;
use super::{ExploreParts, Filter, Mode, backend_name, capability, lsp, mode_of};
use crate::plugins::read::search::text_files;

/// What `dead` and the `to` chains of `impact` print instead of a guess.
pub(crate) const UNAVAILABLE: &str = "not available in text mode";

/// Closes every non-empty text answer, so a reader knows what the lines are worth.
const CAVEAT: &str = "(text search: may include comments, strings and same-named symbols)\n";

/// Definition keywords of the common languages and the kind each one prints. A language that
/// defines with none of these (a C return type, a Java method) is not found; that is the price
/// of needing no grammar.
const KEYWORDS: &[(&str, &str)] = &[
    ("fn", "function"),
    ("def", "function"),
    ("defp", "function"),
    ("defn", "function"),
    ("function", "function"),
    ("func", "function"),
    ("fun", "function"),
    ("proc", "function"),
    ("sub", "function"),
    ("class", "class"),
    ("struct", "struct"),
    ("enum", "enum"),
    ("trait", "trait"),
    ("interface", "interface"),
    ("type", "type"),
    ("module", "module"),
    ("object", "object"),
    ("impl", "impl"),
    ("macro", "macro"),
    ("namespace", "namespace"),
    ("protocol", "protocol"),
    ("record", "record"),
    ("const", "const"),
    ("static", "static"),
];

/// Whether the text backend answers for the project at `root`: pinned with `text`, or `auto`
/// with nothing better, meaning no ready language server (the capability record, so checked once
/// per project, T329.11) and no file a grammar parses.
pub(crate) fn applies(cx: &Ctx, root: &Path) -> bool {
    match mode_of(cx, root) {
        Mode::Text => true,
        Mode::Auto => {
            !capability::server_ready(cx, root, &backend_name(cx, root), lsp::probe)
                && !has_grammar(cx, root)
        }
        Mode::Tags | Mode::Lsp => false,
    }
}

/// One walk that stops at the first file the tags index could parse.
fn has_grammar(cx: &Ctx, root: &Path) -> bool {
    let matcher = Matcher::new(root, &cx.plugin_config::<crate::config::Graph>("graph"));
    matcher
        .walk_builder(root)
        .build()
        .filter_map(Result::ok)
        .any(|e| e.file_type().is_some_and(|t| t.is_file()) && matcher.indexable(e.path()))
}

/// `(relative path, text)` of every searchable file under `root`, with the graph's `include`
/// and `exclude`, the ignore files, and the size limit of the `search` tool.
fn files(cx: &Ctx, root: &Path) -> Result<impl Iterator<Item = (String, String)>> {
    if !root.is_dir() {
        bail!("{} is not a readable directory", root.display());
    }
    let graph = cx.plugin_config::<crate::config::Graph>("graph");
    let max = cx
        .plugin_config::<crate::config::Read>("read")
        .search_max_bytes;
    let base: PathBuf = root.to_path_buf();
    Ok(
        text_files(Matcher::new(root, &graph).walk_builder(root), max).map(move |(path, text)| {
            let rel = path.strip_prefix(&base).unwrap_or(&path);
            let shown = rel.to_string_lossy();
            let shown = if cfg!(windows) {
                shown.replace('\\', "/")
            } else {
                shown.into_owned()
            };
            (shown, text)
        }),
    )
}

/// `\b` only where the name's edge is a word character, so a name like `+=` still matches.
fn word_bounded(name: &str) -> String {
    let edge = |c: Option<char>| match c {
        Some(c) if c.is_alphanumeric() || c == '_' => r"\b",
        _ => "",
    };
    format!(
        "{}{}{}",
        edge(name.chars().next()),
        regex::escape(name),
        edge(name.chars().next_back())
    )
}

fn keyword_alternation() -> String {
    KEYWORDS
        .iter()
        .map(|(k, _)| *k)
        .collect::<Vec<_>>()
        .join("|")
}

fn kind_of(keyword: &str) -> &'static str {
    KEYWORDS
        .iter()
        .find(|(k, _)| *k == keyword)
        .map_or("symbol", |(_, kind)| kind)
}

/// `func (r *T) Name` carries a receiver between the keyword and the name.
const RECEIVER: &str = r"(?:\([^)]*\)\s*)?";

pub(crate) struct Def {
    pub path: String,
    pub line: usize,
    pub kind: &'static str,
    pub text: String,
}

/// Every definition of one name and the files that mention it elsewhere.
pub(crate) struct Scan {
    pub defs: Vec<Def>,
    /// Per file: lines mentioning the name that are not a definition, and the first such line.
    pub mentions: BTreeMap<String, (usize, usize)>,
}

fn scan(cx: &Ctx, root: &Path, name: &str, filter: &Filter) -> Result<Scan> {
    let word = word_bounded(name);
    let def = Regex::new(&format!(
        r"\b({})\s+{RECEIVER}{word}",
        keyword_alternation()
    ))?;
    let mention = Regex::new(&word)?;
    let mut out = Scan {
        defs: Vec::new(),
        mentions: BTreeMap::new(),
    };
    for (path, text) in files(cx, root)? {
        if !filter.path_ok(&path) {
            continue;
        }
        for (i, line) in text.lines().enumerate() {
            if let Some(found) = def.captures(line) {
                let kind = kind_of(&found[1]);
                if filter.kind_ok(kind) {
                    out.defs.push(Def {
                        path: path.clone(),
                        line: i + 1,
                        kind,
                        text: line.trim().to_string(),
                    });
                }
            } else if mention.is_match(line) {
                let slot = out.mentions.entry(path.clone()).or_insert((0, i + 1));
                slot.0 += 1;
            }
        }
    }
    out.defs
        .sort_by(|a, b| a.path.cmp(&b.path).then(a.line.cmp(&b.line)));
    Ok(out)
}

fn defs_text(defs: &[Def]) -> String {
    defs.iter()
        .map(|d| format!("{}:{} {}\n{}\n", d.path, d.line, d.kind, d.text))
        .collect()
}

fn with_caveat(body: String) -> String {
    format!("{body}{CAVEAT}")
}

/// `symbol`: each definition line, with no body (a text search cannot tell where it ends).
pub(crate) fn symbol(cx: &Ctx, root: &Path, name: &str, filter: &Filter) -> Result<String> {
    let found = scan(cx, root, name, filter)?;
    if found.defs.is_empty() {
        return Ok(format!("no definition of {name}{}", filter.scope_note()));
    }
    super::tally::hit(root, name, found.defs.iter().map(|d| d.path.as_str()));
    Ok(with_caveat(defs_text(&found.defs)))
}

/// The same `path ×N (Lline)` rows `callers` prints, but per file: no enclosing definition is known.
fn mention_rows(found: &Scan) -> String {
    found
        .mentions
        .iter()
        .map(|(path, (n, line))| format!("{path} ×{n} (L{line})\n"))
        .collect()
}

pub(crate) fn callers(cx: &Ctx, root: &Path, name: &str, filter: &Filter) -> Result<String> {
    let found = scan(cx, root, name, filter)?;
    if found.mentions.is_empty() {
        return Ok(format!("no references to {name}{}", filter.scope_note()));
    }
    super::tally::hit(root, name, found.mentions.keys());
    Ok(with_caveat(mention_rows(&found)))
}

/// `impact`: the files that mention the name, one level. `to` asks for a chain, which needs the
/// call edges a text search does not have.
pub(crate) fn impact(
    cx: &Ctx,
    root: &Path,
    name: &str,
    depth: u32,
    filter: &Filter,
    to: Option<&str>,
) -> Result<String> {
    if to.is_some_and(|t| !t.is_empty()) {
        return Ok(format!("impact to a symbol: {UNAVAILABLE}"));
    }
    let found = scan(cx, root, name, filter)?;
    if found.mentions.is_empty() {
        return Ok(format!("nothing reaches {name}{}", filter.scope_note()));
    }
    super::tally::hit(root, name, found.mentions.keys());
    let mut out = impact_rows(&found);
    if depth > 1 {
        out.push_str("one level only in text mode\n");
    }
    Ok(with_caveat(out))
}

fn impact_rows(found: &Scan) -> String {
    found
        .mentions
        .iter()
        .map(|(path, (n, _))| format!("1  {path}  ×{n}\n"))
        .collect()
}

/// `outline`: the definition keywords found in one file, `line kind name`.
pub(crate) fn outline(cx: &Ctx, root: &Path, abs: &Path) -> Result<String> {
    let max = cx
        .plugin_config::<crate::config::Read>("read")
        .search_max_bytes;
    if std::fs::metadata(abs)?.len() > max {
        bail!("{} is over plugins.read.search_max_bytes", abs.display());
    }
    let text = std::fs::read_to_string(abs)?;
    let def = Regex::new(&format!(
        r"\b({})\s+{RECEIVER}([A-Za-z_$][\w$]*)",
        keyword_alternation()
    ))?;
    let rows: String = text
        .lines()
        .enumerate()
        .filter_map(|(i, line)| {
            let found = def.captures(line)?;
            Some(format!("{} {} {}\n", i + 1, kind_of(&found[1]), &found[2]))
        })
        .collect();
    if rows.is_empty() {
        return Ok(format!("no definitions in {}", abs.display()));
    }
    super::tally::hit(root, "", [abs.to_string_lossy()]);
    Ok(with_caveat(rows))
}

/// `explore` over scans: a query token is a symbol when something defines it, there is no
/// prefix resolution and no call path, and `impact` is the one-level file list.
pub(crate) struct TextExplore<'a> {
    pub cx: &'a Ctx<'a>,
    pub root: &'a Path,
    pub filter: &'a Filter,
    scans: HashMap<String, Scan>,
}

impl<'a> TextExplore<'a> {
    pub(crate) fn new(cx: &'a Ctx<'a>, root: &'a Path, filter: &'a Filter) -> Self {
        Self {
            cx,
            root,
            filter,
            scans: HashMap::new(),
        }
    }

    /// One walk per name, however many of the four parts ask.
    fn scan(&mut self, name: &str) -> Result<&Scan> {
        if !self.scans.contains_key(name) {
            let found = scan(self.cx, self.root, name, self.filter)?;
            self.scans.insert(name.to_string(), found);
        }
        Ok(&self.scans[name])
    }
}

impl ExploreParts for TextExplore<'_> {
    fn resolve(&mut self, token: &str) -> Result<Vec<String>> {
        let defined = !self.scan(token)?.defs.is_empty();
        Ok(if defined {
            vec![token.to_string()]
        } else {
            vec![]
        })
    }

    fn defs(&mut self, name: &str) -> Result<String> {
        let root = self.root;
        let found = self.scan(name)?;
        if !found.defs.is_empty() {
            super::tally::hit(root, name, found.defs.iter().map(|d| d.path.as_str()));
        }
        Ok(defs_text(&found.defs))
    }

    fn paths(&mut self, _a: &str, _b: &str) -> Result<Vec<String>> {
        Ok(Vec::new())
    }

    fn impact1(&mut self, name: &str) -> Result<(String, usize)> {
        let found = self.scan(name)?;
        Ok((impact_rows(found), found.mentions.len()))
    }

    fn def_count(&mut self, name: &str) -> Result<usize> {
        Ok(self.scan(name)?.defs.len())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;

    fn pinned(tag: &str) -> (crate::plugin::Runtime, PathBuf) {
        let (mut c, dir) = crate::testutil::config(tag);
        c.plugins.graph.backend = "text".into();
        (crate::plugin::Runtime::open(c, tag).unwrap(), dir)
    }

    fn seed(dir: &Path) {
        fs::write(
            dir.join("a.zig"),
            "const std = 1;\nfn alpha() void {\n    beta();\n}\nfn beta() void {}\n// beta is used\n",
        )
        .unwrap();
        fs::write(
            dir.join("b.zig"),
            "fn gamma() void { alpha(); alphabet(); }\n",
        )
        .unwrap();
    }

    /// T329.36: the text backend counts the definition files and the mentioning files it lists.
    #[test]
    fn the_text_backend_counts_what_it_lists() {
        let (cx, dir) = pinned("t32936-text");
        seed(&dir);
        let ctx = Ctx::new(&cx);
        let counted = |f: &dyn Fn() -> String| {
            super::super::tally::arm();
            let answer = f();
            let got = super::super::tally::take();
            (answer, (got.symbols, got.files, got.projects))
        };
        let (_, got) = counted(&|| symbol(&ctx, &dir, "alpha", &Filter::none()).unwrap());
        assert_eq!(got, (1, 1, 1), "defined in a.zig");
        let (answer, got) = counted(&|| callers(&ctx, &dir, "alpha", &Filter::none()).unwrap());
        assert!(answer.starts_with("b.zig"), "{answer}");
        assert_eq!(got, (1, 1, 1), "mentioned in b.zig only");
        let (_, got) = counted(&|| impact(&ctx, &dir, "beta", 1, &Filter::none(), None).unwrap());
        assert_eq!(got, (1, 1, 1), "mentioned in a.zig");
        let (_, got) = counted(&|| symbol(&ctx, &dir, "alp", &Filter::none()).unwrap());
        assert_eq!(got, (0, 0, 0));
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn symbol_finds_definitions_by_word_not_by_prefix() {
        let (cx, dir) = pinned("t32910-symbol");
        seed(&dir);
        let out = symbol(&Ctx::new(&cx), &dir, "alpha", &Filter::none()).unwrap();
        assert!(
            out.starts_with("a.zig:2 function\nfn alpha() void {\n"),
            "{out}"
        );
        assert!(!out.contains("alphabet"), "{out}");
        assert!(out.ends_with(CAVEAT), "{out}");
        let none = symbol(&Ctx::new(&cx), &dir, "alp", &Filter::none()).unwrap();
        assert_eq!(none, "no definition of alp");
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn callers_and_impact_list_mentions_outside_the_definition() {
        let (cx, dir) = pinned("t32910-callers");
        seed(&dir);
        let ctx = Ctx::new(&cx);
        let f = Filter::none();
        let callers = callers(&ctx, &dir, "beta", &f).unwrap();
        assert!(callers.starts_with("a.zig ×2 (L3)\n"), "{callers}");
        let impact = impact(&ctx, &dir, "alpha", 2, &f, None).unwrap();
        assert!(impact.starts_with("1  b.zig  ×1\n"), "{impact}");
        assert!(impact.contains("one level only"), "{impact}");
        let chain = super::impact(&ctx, &dir, "alpha", 1, &f, Some("gamma")).unwrap();
        assert!(chain.contains(UNAVAILABLE), "{chain}");
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn outline_lists_definition_keywords() {
        let (cx, dir) = pinned("t32910-outline");
        seed(&dir);
        let out = outline(&Ctx::new(&cx), &dir, &dir.join("a.zig")).unwrap();
        assert!(
            out.starts_with("1 const std\n2 function alpha\n5 function beta\n"),
            "{out}"
        );
        let _ = fs::remove_dir_all(dir);
    }

    /// The walk is the `search` tool's: ignore files and the graph's `exclude` keep files out.
    #[test]
    fn the_walk_honours_gitignore_and_exclude() {
        let (mut c, dir) = crate::testutil::config("t32910-ignore");
        c.plugins.graph.backend = "text".into();
        c.plugins.graph.exclude = vec!["skip/".into()];
        let cx = crate::plugin::Runtime::open(c, "t32910-ignore").unwrap();
        fs::create_dir_all(dir.join("skip")).unwrap();
        fs::create_dir_all(dir.join(".git")).unwrap();
        fs::write(dir.join(".gitignore"), "ignored.zig\n").unwrap();
        fs::write(dir.join("ignored.zig"), "fn alpha() void {}\n").unwrap();
        fs::write(dir.join("skip/s.zig"), "fn alpha() void {}\n").unwrap();
        fs::write(dir.join("kept.zig"), "fn alpha() void {}\n").unwrap();
        let out = symbol(&Ctx::new(&cx), &dir, "alpha", &Filter::none()).unwrap();
        assert!(out.contains("kept.zig:1"), "{out}");
        assert!(
            !out.contains("ignored.zig") && !out.contains("skip/"),
            "{out}"
        );
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn a_missing_root_is_an_error_not_a_panic() {
        let (cx, dir) = pinned("t32910-missing");
        let gone = dir.join("nope");
        let err = symbol(&Ctx::new(&cx), &gone, "alpha", &Filter::none()).unwrap_err();
        assert!(
            err.to_string().contains("not a readable directory"),
            "{err}"
        );
        let _ = fs::remove_dir_all(dir);
    }
}
