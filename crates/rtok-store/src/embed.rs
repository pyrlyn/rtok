// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! Optional note embeddings (P29): deterministic hash vectors + cosine KNN in SQLite.
//!
//! Deviation from `memory/PLAN.md` v0.2: no sqlite-vec `vec0` — Diesel's bundled SQLite
//! cannot load the extension without cmake/a second process; vectors live in `note_embeddings`.

use std::collections::HashMap;
use std::sync::Arc;

use crate::Result;
use diesel::prelude::*;
use sha2::{Digest, Sha256};

use super::schema::{note_embeddings, notes};
use super::substr;

use rtok_plugin_sdk::NoteHit;

use super::{Store, hex_sha256};

/// A vector source the store cannot build itself, e.g. an embeddings endpoint (T668). The host
/// owns the transport; the store only asks for a vector.
pub trait TextEmbedder: Send + Sync + std::fmt::Debug {
    /// The vector for `text` in the `dimensions` space, or `None` when the backend is down,
    /// refuses the text or answers with something else. The backend logs why; the store then
    /// serves the note search it had before.
    fn embed(&self, text: &str) -> Option<Vec<f32>>;
}

/// Handle compared by identity, so [`EmbedSettings`] keeps its `PartialEq`.
#[derive(Debug, Clone)]
pub struct Remote(pub Arc<dyn TextEmbedder>);

impl PartialEq for Remote {
    fn eq(&self, other: &Self) -> bool {
        Arc::ptr_eq(&self.0, &other.0)
    }
}

/// `[plugins.memory.embed]` as plain values. The host config converts into this; the store
/// does not read the config crate.
#[derive(Debug, Clone, PartialEq)]
pub struct EmbedSettings {
    pub enabled: bool,
    pub provider: String,
    pub model: String,
    pub dimensions: u32,
    pub hybrid: bool,
    /// Set for `provider = "http"`. `None` keeps the deterministic hash embedder.
    pub remote: Option<Remote>,
}

impl Default for EmbedSettings {
    fn default() -> Self {
        Self {
            enabled: false,
            provider: "local".into(),
            model: "all-MiniLM-L6-v2".into(),
            dimensions: 384,
            hybrid: true,
            remote: None,
        }
    }
}

const RRF_K: f32 = 60.0;

pub(crate) fn note_embed_text(title: &str, body: &str) -> String {
    format!("{title}\n{body}")
}

/// Stored in `note_embeddings.model` after `[embed] model`. Bump it whenever [`hash_embed`]
/// changes: every older vector then reads as stale and [`Store::embed_stale`] redoes it.
const SCHEME: &str = "hash2";

/// Remote vectors live in another space than hash vectors, so they get their own key: neither
/// leg ever scores the other's rows, and switching providers re-embeds through `embed_stale`.
const REMOTE_SCHEME: &str = "remote1";

fn model_key(cfg: &EmbedSettings) -> String {
    let scheme = if cfg.remote.is_some() {
        REMOTE_SCHEME
    } else {
        SCHEME
    };
    format!("{}+{scheme}", cfg.model)
}

/// The vector of `text` under `cfg`: hash, or the remote backend's unit-length answer. `None`
/// only for a remote that failed or answered in the wrong `dimensions`.
fn embed_text(text: &str, cfg: &EmbedSettings) -> Option<Vec<f32>> {
    let Some(Remote(remote)) = &cfg.remote else {
        return Some(hash_embed(text, cfg.dimensions));
    };
    let mut v = remote.embed(text)?;
    if v.len() != cfg.dimensions as usize {
        return None;
    }
    l2_normalize(&mut v);
    Some(v)
}

fn dims(cfg: &EmbedSettings) -> i32 {
    i32::try_from(cfg.dimensions).unwrap_or(384)
}

/// Deterministic feature hash — offline tests, no ONNX/OpenAI (Gate P29). A token lands in
/// four slots, one per independent 8-byte slice of its SHA-256. The first scheme used four
/// consecutive slots (`h..h+3`) with signs from adjacent bits of one hash, so two tokens
/// that collided did so in runs; that noise decided the P29 hybrid ranking. Words are Unicode:
/// splitting on ASCII alphanumerics embedded a Cyrillic note as the zero vector.
pub fn hash_embed(text: &str, dims: u32) -> Vec<f32> {
    let dims = u64::from(dims.max(1));
    let mut v = vec![0f32; dims as usize];
    for token in text
        .split(|c: char| !c.is_alphanumeric())
        .filter(|t| t.chars().nth(1).is_some())
    {
        let digest = Sha256::digest(token.to_lowercase().as_bytes());
        for chunk in digest.as_chunks::<8>().0 {
            let h = u64::from_le_bytes(*chunk);
            v[(h % dims) as usize] += if h >> 63 == 0 { 1.0 } else { -1.0 };
        }
    }
    l2_normalize(&mut v);
    v
}

fn l2_normalize(v: &mut [f32]) {
    let n = v.iter().map(|x| x * x).sum::<f32>().sqrt();
    if n > f32::EPSILON {
        for x in v {
            *x /= n;
        }
    }
}

pub fn cosine(a: &[f32], b: &[f32]) -> f32 {
    a.iter().zip(b).map(|(x, y)| x * y).sum()
}

fn embed_to_blob(v: &[f32]) -> Vec<u8> {
    v.iter().flat_map(|f| f.to_le_bytes()).collect()
}

fn blob_to_embed(blob: &[u8], dims: u32) -> Option<Vec<f32>> {
    let dims = dims as usize;
    if blob.len() != dims * 4 {
        return None;
    }
    let mut out = Vec::with_capacity(dims);
    for c in blob.as_chunks::<4>().0 {
        out.push(f32::from_le_bytes(*c));
    }
    Some(out)
}

impl Store {
    pub fn upsert_note_embedding(
        &self,
        note_id: i32,
        title: &str,
        body: &str,
        cfg: &EmbedSettings,
    ) -> Result<()> {
        self.put_embedding(note_id, title, body, cfg).map(|_| ())
    }

    /// `false` when a remote backend gave no vector; the note then has none under this key and
    /// `embed_stale` tries again on a later search. Never a hash vector under the remote key.
    fn put_embedding(
        &self,
        note_id: i32,
        title: &str,
        body: &str,
        cfg: &EmbedSettings,
    ) -> Result<bool> {
        if !cfg.enabled {
            return Ok(true);
        }
        let text = note_embed_text(title, body);
        let hash = hex_sha256(text.as_bytes());
        // The note's own text and nothing else. Index time used to append hook/database words
        // to any body mentioning "hook", tuned to the P29 fixture, so such notes outranked
        // better matches on every hook-flavoured query.
        let Some(vector) = embed_text(&text, cfg) else {
            return Ok(false);
        };
        let blob = embed_to_blob(&vector);
        let mut conn = self.lock()?;
        let model = model_key(cfg);
        let dims = dims(cfg);
        super::sql_ext::UpsertNoteEmbedding {
            note_id,
            model,
            dims,
            text_hash: hash,
            vector: blob,
        }
        .execute(&mut *conn)?;
        Ok(true)
    }

    /// Embed every note whose vector is missing or was made under another model, [`SCHEME`]
    /// or `dimensions`. Only `mem_save` with `[embed]` on wrote vectors, so notes saved before
    /// the flag was turned on, or before `dimensions` changed, never reached the KNN leg.
    fn embed_stale(&self, cfg: &EmbedSettings) -> Result<()> {
        if !cfg.enabled {
            return Ok(());
        }
        let model = model_key(cfg);
        let dims = dims(cfg);
        let stale: Vec<(i32, String, String)> = notes::table
            .left_join(note_embeddings::table)
            .filter(
                note_embeddings::note_id
                    .is_null()
                    .or(note_embeddings::model.ne(&model))
                    .or(note_embeddings::dims.ne(dims)),
            )
            .select((notes::id, notes::title, notes::body))
            .load(&mut *self.lock()?)?;
        for (id, title, body) in stale {
            // A remote that fails once is down for this search: do not wait out a timeout
            // per note.
            if !self.put_embedding(id, &title, &body, cfg)? {
                break;
            }
        }
        Ok(())
    }

    pub fn search_notes_embed(
        &self,
        query: &str,
        limit: u32,
        cfg: &EmbedSettings,
    ) -> Result<Vec<NoteHit>> {
        // The query first: a remote that is down must cost one failed call, not one per stale
        // note, and the search then stays the plain note search it was without `[embed]`.
        let Some(qv) = embed_text(query, cfg) else {
            return self.search_notes(query, limit);
        };
        self.embed_stale(cfg)?;
        self.search_notes_knn(&qv, limit, cfg)
    }

    /// Cosine KNN over vectors already in `note_embeddings`. Does not call [`Store::embed_stale`].
    fn search_notes_knn(
        &self,
        qv: &[f32],
        limit: u32,
        cfg: &EmbedSettings,
    ) -> Result<Vec<NoteHit>> {
        let mut conn = self.lock()?;
        // Only vectors of the query's own model, scheme and `dimensions` are scored: `cosine`
        // zips to the shorter vector, so a stale 384-dim row against an 8-dim query ranked on
        // noise. `search_notes_embed` has just rewritten those rows; the stored hybrid leg
        // leaves them unread.
        let rows: Vec<(i32, String, String, Vec<u8>, i32)> = note_embeddings::table
            .inner_join(notes::table)
            .filter(note_embeddings::model.eq(model_key(cfg)))
            .filter(note_embeddings::dims.eq(dims(cfg)))
            .filter(notes::retired.is_null())
            .select((
                notes::id,
                notes::title,
                substr(notes::body, 1, 120),
                note_embeddings::vector,
                note_embeddings::dims,
            ))
            .load(&mut *conn)?;
        let mut scored: Vec<(f32, NoteHit)> = rows
            .into_iter()
            .filter_map(|(id, title, snippet, vector, dims)| {
                let v = blob_to_embed(&vector, dims as u32)?;
                Some((cosine(qv, &v), NoteHit { id, title, snippet }))
            })
            .collect();
        scored.sort_by(|a, b| b.0.partial_cmp(&a.0).unwrap_or(std::cmp::Ordering::Equal));
        Ok(scored
            .into_iter()
            .take(limit.max(1) as usize)
            .map(|(_, h)| h)
            .collect())
    }

    pub fn search_notes_hybrid(
        &self,
        query: &str,
        limit: u32,
        cfg: &EmbedSettings,
    ) -> Result<Vec<NoteHit>> {
        let fts = self.search_notes(query, limit.saturating_mul(2).max(limit))?;
        let knn = self.search_notes_embed(query, limit.saturating_mul(2).max(limit), cfg)?;
        Ok(rrf_merge(&fts, &knn, limit))
    }

    /// How many rows `note_embeddings` holds. The hook uses this to skip KNN when nothing is stored.
    pub fn note_embedding_count(&self) -> Result<i64> {
        use diesel::dsl::count_star;
        note_embeddings::table
            .select(count_star())
            .first(&mut *self.lock()?)
            .map_err(Into::into)
    }

    /// Same RRF as [`Store::search_notes_hybrid`], over vectors already on disk.
    ///
    /// Does not call `embed_stale`: a missing or stale row is absent from the KNN leg, never
    /// rewritten. An empty embedding table returns the FTS list at `limit`.
    pub fn search_notes_hybrid_stored(
        &self,
        query: &str,
        limit: u32,
        cfg: &EmbedSettings,
    ) -> Result<Vec<NoteHit>> {
        // The hook never waits on the network (10 ms budget), and a remote query vector needs it.
        if cfg.remote.is_some() || self.note_embedding_count()? == 0 {
            return self.search_notes(query, limit);
        }
        let pool = limit.saturating_mul(2).max(limit);
        let fts = self.search_notes(query, pool)?;
        let knn = self.search_notes_knn(&hash_embed(query, cfg.dimensions), pool, cfg)?;
        Ok(rrf_merge(&fts, &knn, limit))
    }
}

pub fn rrf_merge(fts: &[NoteHit], knn: &[NoteHit], limit: u32) -> Vec<NoteHit> {
    rrf_merge_lists(&[fts, knn], limit)
}

/// Reciprocal rank fusion over any number of ranked lists (T374 adds the file-linked list to
/// the text list). The first list to name a note supplies its title and snippet.
pub fn rrf_merge_lists(lists: &[&[NoteHit]], limit: u32) -> Vec<NoteHit> {
    let mut scores: HashMap<i32, f32> = HashMap::new();
    let mut hits: HashMap<i32, NoteHit> = HashMap::new();
    for list in lists {
        for (rank, h) in list.iter().enumerate() {
            *scores.entry(h.id).or_default() += 1.0 / (RRF_K + rank as f32 + 1.0);
            hits.entry(h.id).or_insert_with(|| h.clone());
        }
    }
    let mut order: Vec<(i32, f32)> = scores.into_iter().collect();
    // T373: equal RRF scores break ties by note id ascending (byte-stable across runs).
    order.sort_by(|a, b| {
        b.1.partial_cmp(&a.1)
            .unwrap_or(std::cmp::Ordering::Equal)
            .then_with(|| a.0.cmp(&b.0))
    });
    order
        .into_iter()
        .take(limit.max(1) as usize)
        .filter_map(|(id, _)| hits.remove(&id))
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    /// T373: equal RRF scores sort by note id ascending, identical across runs.
    #[test]
    fn rrf_merge_breaks_score_ties_by_note_id() {
        // Each note appears in only one list at rank 0 → equal scores 1/(RRF_K+1).
        let fts = vec![NoteHit {
            id: 30,
            title: "c".into(),
            snippet: String::new(),
        }];
        let knn = vec![NoteHit {
            id: 10,
            title: "a".into(),
            snippet: String::new(),
        }];
        let first = rrf_merge(&fts, &knn, 10);
        let ids: Vec<i32> = first.iter().map(|h| h.id).collect();
        // Tie on score → id ascending: 10 before 30.
        assert_eq!(ids, vec![10, 30], "{ids:?}");
        for _ in 0..50 {
            assert_eq!(
                rrf_merge(&fts, &knn, 10)
                    .iter()
                    .map(|h| h.id)
                    .collect::<Vec<_>>(),
                ids
            );
        }
    }

    #[test]
    fn hash_embed_is_deterministic_and_unit_length() {
        let a = hash_embed("hello world", 384);
        let b = hash_embed("hello world", 384);
        assert_eq!(a, b);
        let n: f32 = a.iter().map(|x| x * x).sum::<f32>().sqrt();
        assert!((n - 1.0).abs() < 1e-5);
    }

    /// ASCII-only words made every Cyrillic note the zero vector, unreachable by KNN.
    #[test]
    fn a_cyrillic_note_has_a_vector() {
        let a = hash_embed("хуки не блокируют", 64);
        assert!(a.iter().any(|x| *x != 0.0));
        assert!(cosine(&a, &hash_embed("Хуки НЕ блокируют", 64)) > 0.999);
        assert!(cosine(&a, &hash_embed("миграция схемы базы", 64)) < 0.5);
    }

    #[test]
    fn hook_async_text_is_closer_than_unrelated() {
        let cfg = EmbedSettings {
            enabled: true,
            dimensions: 384,
            ..EmbedSettings::default()
        };
        let query = "why not use an async database library for hooks";
        let qv = hash_embed(query, cfg.dimensions);
        let pv = hash_embed(
            &note_embed_text(
                "p29-gate-arctic-tern",
                "Hooks must exit in ≤10 ms fail-open; async ORM rejected — Diesel stays sync on the hook path (D13).",
            ),
            cfg.dimensions,
        );
        let dv = hash_embed(
            &note_embed_text(
                "p29-decoy-etl-batch",
                "Storage indexing and schema migration patterns for batch ETL pipelines in data warehouses.",
            ),
            cfg.dimensions,
        );
        assert!(
            cosine(&qv, &pv) > cosine(&qv, &dv),
            "planted {:.4} vs decoy {:.4}",
            cosine(&qv, &pv),
            cosine(&qv, &dv)
        );
    }

    /// A vector from another `dimensions` and a note saved while `[embed]` was off both reach
    /// KNN: search re-embeds them first instead of scoring or skipping them.
    #[test]
    fn stale_and_missing_vectors_are_embedded_before_knn() {
        let s = Store::open_in_memory().unwrap();
        let wide = EmbedSettings {
            enabled: true,
            dimensions: 384,
            ..EmbedSettings::default()
        };
        let narrow = EmbedSettings {
            dimensions: 8,
            ..wide.clone()
        };
        let id = s.insert_note(None, "note", "t", "alpha beta").unwrap();
        s.upsert_note_embedding(id, "t", "alpha beta", &wide)
            .unwrap();
        s.insert_note(None, "note", "u", "alpha gamma").unwrap(); // saved with embed off
        assert_eq!(s.search_notes_embed("alpha", 5, &narrow).unwrap().len(), 2);
    }

    /// A note that only says "hook" must not outrank one that holds both query words: the
    /// old index-time boost padded it with `hooks`/`path` and it won.
    #[test]
    fn a_passing_mention_of_hook_is_not_boosted() {
        let s = Store::open_in_memory().unwrap();
        let cfg = EmbedSettings {
            enabled: true,
            dimensions: 384,
            ..EmbedSettings::default()
        };
        for (title, body) in [
            ("aa", "hook"),
            ("bb", "hooks path guide for new contributors on the team"),
        ] {
            let id = s.insert_note(None, "note", title, body).unwrap();
            s.upsert_note_embedding(id, title, body, &cfg).unwrap();
        }
        let top = s.search_notes_embed("hooks path", 1, &cfg).unwrap();
        assert_eq!(top[0].title, "bb");
    }

    #[test]
    fn fts_phrase_unchanged_for_flag_off_path() {
        use super::super::fts_phrase_query;
        assert_eq!(
            fts_phrase_query("Diesel sync"),
            Some("\"Diesel\" \"sync\"".into())
        );
    }

    fn embedding_rows(s: &Store) -> Vec<(i32, i32, String)> {
        note_embeddings::table
            .select((
                note_embeddings::note_id,
                note_embeddings::dims,
                note_embeddings::text_hash,
            ))
            .order(note_embeddings::note_id)
            .load(&mut *s.lock().unwrap())
            .unwrap()
    }

    fn hit_ids(hits: &[NoteHit]) -> Vec<i32> {
        hits.iter().map(|h| h.id).collect()
    }

    /// An empty embedding table is the FTS list, and the search does not write a vector.
    #[test]
    fn stored_hybrid_returns_the_fts_list_when_the_table_is_empty() {
        let s = Store::open_in_memory().unwrap();
        let cfg = EmbedSettings {
            enabled: true,
            hybrid: true,
            ..EmbedSettings::default()
        };
        s.insert_note(None, "note", "walrus", "the walrus journal lives here")
            .unwrap();
        assert_eq!(s.note_embedding_count().unwrap(), 0);
        let fts = s.search_notes("walrus journal", 5).unwrap();
        let hybrid = s
            .search_notes_hybrid_stored("walrus journal", 5, &cfg)
            .unwrap();
        assert_eq!(hit_ids(&hybrid), hit_ids(&fts));
        assert_eq!(
            hybrid.iter().map(|h| &h.snippet).collect::<Vec<_>>(),
            fts.iter().map(|h| &h.snippet).collect::<Vec<_>>()
        );
        assert_eq!(s.note_embedding_count().unwrap(), 0);
    }

    /// A stored vector that FTS misses stays eligible, and a stale or missing row is not rewritten.
    #[test]
    fn stored_hybrid_hits_a_stored_vector_and_does_not_write() {
        let s = Store::open_in_memory().unwrap();
        let cfg = EmbedSettings {
            enabled: true,
            dimensions: 384,
            hybrid: true,
            ..EmbedSettings::default()
        };
        let planted_title = "p29-gate-arctic-tern";
        let planted_body = "Hooks must exit in ≤10 ms fail-open; async ORM rejected — Diesel stays sync on the hook path (D13).";
        let planted = s
            .insert_note(None, "decision", planted_title, planted_body)
            .unwrap();
        s.upsert_note_embedding(planted, planted_title, planted_body, &cfg)
            .unwrap();
        let decoy_title = "p29-decoy-etl-batch";
        let decoy_body = "Storage indexing and schema migration patterns for batch ETL pipelines in data warehouses.";
        let decoy = s
            .insert_note(None, "decision", decoy_title, decoy_body)
            .unwrap();
        s.upsert_note_embedding(decoy, decoy_title, decoy_body, &cfg)
            .unwrap();
        // Saved with embeddings off: no row, and the hook must not create one.
        let missing = s
            .insert_note(None, "note", "unembedded", "alpha gamma extra")
            .unwrap();
        let query = "why not use an async database library for hooks";
        let fts = s.search_notes(query, 10).unwrap();
        assert!(
            fts.iter().all(|h| h.id != planted),
            "FTS must miss the planted note: {fts:?}"
        );
        let before = embedding_rows(&s);
        let narrow = EmbedSettings {
            dimensions: 8,
            ..cfg.clone()
        };
        // Wrong dimensions: the stored 384-dim rows are stale. Scoring them would be noise,
        // and rewriting them is `embed_stale`, which this path must not call.
        let _ = s.search_notes_hybrid_stored(query, 10, &narrow).unwrap();
        assert_eq!(embedding_rows(&s), before, "stale vectors stay as stored");
        assert!(before.iter().all(|(id, _, _)| *id != missing));

        let hybrid = s.search_notes_hybrid_stored(query, 10, &cfg).unwrap();
        assert!(
            hybrid.iter().any(|h| h.id == planted),
            "stored vector is eligible: {hybrid:?}"
        );
        let again = s.search_notes_hybrid_stored(query, 10, &cfg).unwrap();
        assert_eq!(hit_ids(&hybrid), hit_ids(&again));
        assert_eq!(embedding_rows(&s), before);
    }
}

/// T668: a stub backend stands in for the HTTP embedder; the wire has its own tests in the host.
#[cfg(test)]
mod remote_tests {
    use super::*;
    use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};

    /// Concept axes: "car" and "automobile" share one, which FTS cannot see.
    #[derive(Debug, Default)]
    struct Stub {
        down: AtomicBool,
        /// Fails notes (their text has a title line) but answers queries.
        notes_down: AtomicBool,
        calls: AtomicUsize,
    }

    impl TextEmbedder for Stub {
        fn embed(&self, text: &str) -> Option<Vec<f32>> {
            self.calls.fetch_add(1, Ordering::SeqCst);
            let note = text.contains('\n');
            if self.down.load(Ordering::SeqCst) || (note && self.notes_down.load(Ordering::SeqCst))
            {
                return None;
            }
            let t = text.to_lowercase();
            let axis = |words: &[&str]| f32::from(u8::from(words.iter().any(|w| t.contains(w))));
            Some(vec![
                axis(&["car", "automobile"]),
                axis(&["banana", "fruit"]),
                0.1,
            ])
        }
    }

    fn setup(stub: &Arc<Stub>) -> (Store, EmbedSettings) {
        let s = Store::open_in_memory().unwrap();
        let cfg = EmbedSettings {
            enabled: true,
            dimensions: 3,
            remote: Some(Remote(stub.clone())),
            ..EmbedSettings::default()
        };
        for (title, body) in [
            ("fruit bowl", "a ripe banana"),
            ("garage", "the car is red"),
        ] {
            s.insert_note(None, "note", title, body).unwrap();
        }
        (s, cfg)
    }

    #[test]
    fn remote_vectors_rank_a_synonym_that_fts_misses() {
        let stub = Arc::new(Stub::default());
        let (s, cfg) = setup(&stub);
        assert!(s.search_notes("automobile", 5).unwrap().is_empty());
        let knn = s.search_notes_embed("automobile", 1, &cfg).unwrap();
        assert_eq!(hit_titles(&knn), ["garage"]);
        let hybrid = s.search_notes_hybrid("automobile", 1, &cfg).unwrap();
        assert_eq!(hit_titles(&hybrid), ["garage"]);
    }

    #[test]
    fn a_failing_remote_serves_the_plain_note_search_and_writes_nothing() {
        let stub = Arc::new(Stub::default());
        let (s, cfg) = setup(&stub);
        stub.down.store(true, Ordering::SeqCst);
        for q in ["car", "automobile"] {
            let plain = s.search_notes(q, 5).unwrap();
            assert_eq!(
                hit_titles(&s.search_notes_embed(q, 5, &cfg).unwrap()),
                hit_titles(&plain)
            );
            assert_eq!(
                hit_titles(&s.search_notes_hybrid(q, 5, &cfg).unwrap()),
                hit_titles(&plain)
            );
        }
        assert_eq!(
            s.note_embedding_count().unwrap(),
            0,
            "no hash vector either"
        );
        // One failed query vector per search; the stale notes are never tried.
        assert_eq!(stub.calls.load(Ordering::SeqCst), 4);
    }

    #[test]
    fn indexing_stops_at_the_first_failure_and_resumes_later() {
        let stub = Arc::new(Stub::default());
        let (s, cfg) = setup(&stub);
        stub.notes_down.store(true, Ordering::SeqCst);
        s.search_notes_embed("car", 5, &cfg).unwrap();
        // The query, then the first stale note; the second note is not attempted.
        assert_eq!(stub.calls.load(Ordering::SeqCst), 2);
        assert_eq!(s.note_embedding_count().unwrap(), 0);
        stub.notes_down.store(false, Ordering::SeqCst);
        s.search_notes_embed("car", 5, &cfg).unwrap();
        assert_eq!(s.note_embedding_count().unwrap(), 2);
    }

    #[test]
    fn the_hook_leg_never_calls_the_remote() {
        let stub = Arc::new(Stub::default());
        let (s, cfg) = setup(&stub);
        s.search_notes_embed("car", 5, &cfg).unwrap();
        let calls = stub.calls.load(Ordering::SeqCst);
        let hits = s.search_notes_hybrid_stored("car", 5, &cfg).unwrap();
        assert_eq!(
            hit_titles(&hits),
            hit_titles(&s.search_notes("car", 5).unwrap())
        );
        assert_eq!(stub.calls.load(Ordering::SeqCst), calls);
    }

    #[test]
    fn a_vector_of_the_wrong_length_is_dropped() {
        let stub = Arc::new(Stub::default());
        let (s, mut cfg) = setup(&stub);
        cfg.dimensions = 8;
        s.search_notes_embed("car", 5, &cfg).unwrap();
        assert_eq!(s.note_embedding_count().unwrap(), 0);
    }

    #[test]
    fn remote_and_hash_vectors_never_share_a_key() {
        let stub = Arc::new(Stub::default());
        let (_, cfg) = setup(&stub);
        let hash = EmbedSettings {
            remote: None,
            ..cfg.clone()
        };
        assert_eq!(
            model_key(&hash),
            "all-MiniLM-L6-v2+hash2",
            "unchanged for hash"
        );
        assert_ne!(model_key(&cfg), model_key(&hash));
    }

    #[test]
    fn disabled_embeddings_never_reach_the_remote() {
        let stub = Arc::new(Stub::default());
        let (s, mut cfg) = setup(&stub);
        cfg.enabled = false;
        s.upsert_note_embedding(1, "garage", "the car is red", &cfg)
            .unwrap();
        assert_eq!(stub.calls.load(Ordering::SeqCst), 0);
        assert_eq!(s.note_embedding_count().unwrap(), 0);
    }

    fn hit_titles(hits: &[NoteHit]) -> Vec<String> {
        hits.iter().map(|h| h.title.clone()).collect()
    }
}
