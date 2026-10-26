// Port of crates/rtok-store/src/embed.rs (P29): optional note embeddings — deterministic hash
// vectors (or a host-supplied remote embedder) and cosine KNN over `note_embeddings`.
package rtok.store

import rtok.plugin.sdk.NoteHit
import rtok.sys.Sha256
import kotlin.math.sqrt

/** A vector source the store cannot build itself (T668); the host owns the transport. */
fun interface TextEmbedder {
    /** The vector for `text`, or null when the backend is down or refuses; the store then serves plain note search. */
    fun embed(text: String): FloatArray?
}

/** `[plugins.memory.embed]` as plain values; `remote` set for `provider = "http"`. */
data class EmbedSettings(
    val enabled: Boolean = false, val provider: String = "local", val model: String = "all-MiniLM-L6-v2",
    val dimensions: UInt = 384u, val hybrid: Boolean = true, val remote: TextEmbedder? = null,
)

private const val RRF_K = 60.0f
/** Rust `f32::EPSILON`. */
private const val F32_EPSILON = 1.1920929E-7f
/** Bump whenever [hashEmbed] changes: older vectors then read as stale. */
private const val SCHEME = "hash2"
/** Remote vectors live in another space and get their own key. */
private const val REMOTE_SCHEME = "remote1"

internal fun noteEmbedText(title: String, body: String) = "$title\n$body"
internal fun modelKey(cfg: EmbedSettings) = "${cfg.model}+${if (cfg.remote != null) REMOTE_SCHEME else SCHEME}"
private fun dims(cfg: EmbedSettings) = cfg.dimensions.takeIf { it <= Int.MAX_VALUE.toUInt() }?.toInt() ?: 384

/** Hash vector, or the remote's unit-length answer; null only for a failed or wrong-length remote. */
private fun embedText(text: String, cfg: EmbedSettings): FloatArray? {
    val remote = cfg.remote ?: return hashEmbed(text, cfg.dimensions)
    val v = remote.embed(text) ?: return null
    if (v.size.toUInt() != cfg.dimensions) return null
    l2Normalize(v); return v
}

/** Deterministic feature hash: each Unicode word of 2+ chars lands in four slots, one per 8-byte slice of its SHA-256. */
fun hashEmbed(text: String, dims: UInt): FloatArray {
    val d = maxOf(dims, 1u).toULong()
    val v = FloatArray(d.toInt())
    for (token in words(text)) {
        if (token.codePointCount() < 2) continue
        val digest = Sha256.digest(token.lowercase().encodeToByteArray())
        for (c in 0 until 4) {
            var h = 0uL
            for (b in 7 downTo 0) h = (h shl 8) or (digest[c * 8 + b].toUByte().toULong())
            v[(h % d).toInt()] += if (h shr 63 == 0uL) 1.0f else -1.0f
        }
    }
    l2Normalize(v); return v
}

/** Rust `split(|c| !c.is_alphanumeric())`, by code point; a supplementary-plane character (emoji) separates. */
private fun words(text: String): List<String> {
    val out = ArrayList<String>(); val cur = StringBuilder(); var i = 0
    while (i < text.length) {
        val pair = text[i].isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()
        val alnum = !pair && text[i].isLetterOrDigit()
        if (alnum) cur.append(text[i]) else { out += cur.toString(); cur.clear() }
        i += if (pair) 2 else 1
    }
    out += cur.toString(); return out
}
private fun String.codePointCount(): Int { var n = 0; var i = 0; while (i < length) { i += if (this[i].isHighSurrogate() && i + 1 < length) 2 else 1; n++ }; return n }

private fun l2Normalize(v: FloatArray) {
    var s = 0f; for (x in v) s += x * x
    val n = sqrt(s); if (n > F32_EPSILON) for (i in v.indices) v[i] /= n
}

fun cosine(a: FloatArray, b: FloatArray): Float { var s = 0f; for (i in 0 until minOf(a.size, b.size)) s += a[i] * b[i]; return s }

private fun embedToBlob(v: FloatArray): ByteArray = ByteArray(v.size * 4).also { out ->
    v.forEachIndexed { i, f -> val bits = f.toRawBits(); for (k in 0 until 4) out[i * 4 + k] = (bits ushr (8 * k)).toByte() }
}
private fun blobToEmbed(blob: ByteArray, dims: Int): FloatArray? {
    if (blob.size != dims * 4) return null
    return FloatArray(dims) { i -> Float.fromBits((0 until 4).fold(0) { acc, k -> acc or ((blob[i * 4 + k].toInt() and 0xff) shl (8 * k)) }) }
}

fun Store.upsertNoteEmbedding(noteId: Int, title: String, body: String, cfg: EmbedSettings) { putEmbedding(noteId, title, body, cfg) }

/** False when a remote gave no vector; never a hash vector under the remote key. */
private fun Store.putEmbedding(noteId: Int, title: String, body: String, cfg: EmbedSettings): Boolean {
    if (!cfg.enabled) return true
    val text = noteEmbedText(title, body)
    val vector = embedText(text, cfg) ?: return false
    conn { it.execute("INSERT INTO note_embeddings (note_id, model, dims, text_hash, vector) VALUES (?, ?, ?, ?, ?) ON CONFLICT(note_id) DO UPDATE SET " +
        "model = excluded.model, dims = excluded.dims, text_hash = excluded.text_hash, embedded_at = unixepoch(), vector = excluded.vector " +
        "WHERE excluded.text_hash != note_embeddings.text_hash OR excluded.model != note_embeddings.model OR excluded.dims != note_embeddings.dims",
        noteId, modelKey(cfg), dims(cfg), hexSha256(text.encodeToByteArray()), embedToBlob(vector)) }
    return true
}

/** Embed every note whose vector is missing or made under another key or `dimensions`; stop at the first remote failure. */
private fun Store.embedStale(cfg: EmbedSettings) {
    if (!cfg.enabled) return
    val stale = conn { it.query("SELECT notes.id, notes.title, notes.body FROM notes LEFT JOIN note_embeddings ON note_embeddings.note_id = notes.id " +
        "WHERE note_embeddings.note_id IS NULL OR note_embeddings.model <> ? OR note_embeddings.dims <> ?", modelKey(cfg), dims(cfg)) { r -> Triple(r.int(0), r.string(1), r.string(2)) } }
    for ((id, title, body) in stale) if (!putEmbedding(id, title, body, cfg)) break
}

/** The query vector first, so a down remote costs one call and the search falls back to plain note search. */
fun Store.searchNotesEmbed(query: String, limit: UInt, cfg: EmbedSettings): List<NoteHit> {
    val qv = embedText(query, cfg) ?: return searchNotes(query, limit)
    embedStale(cfg); return searchNotesKnn(qv, limit, cfg)
}

/** Cosine KNN over stored vectors of the query's own key and `dimensions`. */
private fun Store.searchNotesKnn(qv: FloatArray, limit: UInt, cfg: EmbedSettings): List<NoteHit> {
    val rows = conn { it.query("SELECT notes.id, notes.title, substr(notes.body, 1, 120), note_embeddings.vector, note_embeddings.dims FROM note_embeddings " +
        "INNER JOIN notes ON notes.id = note_embeddings.note_id WHERE note_embeddings.model = ? AND note_embeddings.dims = ? AND notes.retired IS NULL", modelKey(cfg), dims(cfg)) { r ->
        blobToEmbed(r.blob(3), r.int(4))?.let { v -> cosine(qv, v) to NoteHit(r.int(0), r.string(1), r.string(2)) } } }
    return rows.filterNotNull().sortedByDescending { it.first }.take(maxOf(limit, 1u).toInt()).map { it.second }
}

private fun pool(limit: UInt) = if (limit > UInt.MAX_VALUE / 2u) UInt.MAX_VALUE else maxOf(limit * 2u, limit)

fun Store.searchNotesHybrid(query: String, limit: UInt, cfg: EmbedSettings): List<NoteHit> =
    rrfMerge(searchNotes(query, pool(limit)), searchNotesEmbed(query, pool(limit), cfg), limit)

fun Store.noteEmbeddingCount(): Long = conn { it.longOf("SELECT count(*) FROM note_embeddings")!! }

/** RRF over vectors already on disk; never re-embeds, never calls a remote (the hook's 10 ms budget). */
fun Store.searchNotesHybridStored(query: String, limit: UInt, cfg: EmbedSettings): List<NoteHit> {
    if (cfg.remote != null || noteEmbeddingCount() == 0L) return searchNotes(query, limit)
    val p = pool(limit)
    return rrfMerge(searchNotes(query, p), searchNotesKnn(hashEmbed(query, cfg.dimensions), p, cfg), limit)
}

fun rrfMerge(fts: List<NoteHit>, knn: List<NoteHit>, limit: UInt) = rrfMergeLists(listOf(fts, knn), limit)

/** Reciprocal rank fusion; the first list naming a note supplies its title and snippet; ties by id ascending (T373). */
fun rrfMergeLists(lists: List<List<NoteHit>>, limit: UInt): List<NoteHit> {
    val scores = HashMap<Int, Float>(); val hits = HashMap<Int, NoteHit>()
    for (list in lists) list.forEachIndexed { rank, h -> scores[h.id] = (scores[h.id] ?: 0f) + 1.0f / (RRF_K + rank.toFloat() + 1.0f); hits.getOrPut(h.id) { h } }
    return scores.entries.sortedWith(compareByDescending<Map.Entry<Int, Float>> { it.value }.thenBy { it.key })
        .take(maxOf(limit, 1u).toInt()).mapNotNull { hits[it.key] }
}
