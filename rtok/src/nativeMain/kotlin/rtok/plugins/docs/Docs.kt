// Port of src/plugins/docs/mod.rs — local crate docs from `Cargo.lock` + a cached docs.rs rustdoc JSON
// index (T455), and the workspace's `llms.txt` as crate `llms`.
package rtok.plugins.docs

import kotlinx.serialization.json.*
import rtok.config.Toml
import rtok.log.now
import rtok.plugin.Runtime
import rtok.plugin.sdk.*
import rtok.store.*
import rtok.sys.*

object DocsPlugin : Plugin {
    override fun manifest() = Manifest("docs", listOf(Surface.Mcp, Surface.Cli), false)
    override fun dashboardPage() = DashboardPage("Docs", "Ranked rustdoc snippets from Cargo.lock, fetched once and searched offline.", true)
    override fun mcpTools() = listOf(
        ToolDef("docs_resolve", "Exact crate name and version from Cargo.lock for a dependency name.",
            Json.parseToJsonElement("""{"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}""")),
        ToolDef("docs_query", "FTS snippets for one cached crate version; ids only, docs_get loads a body.",
            Json.parseToJsonElement("""{"type":"object","properties":{"name":{"type":"string"},"version":{"type":"string"},"query":{"type":"string"},"question":{"type":"string","description":"alias of query"},"userQuery":{"type":"string","description":"alias of query"}},"required":["name"],"anyOf":[{"required":["query"]},{"required":["question"]},{"required":["userQuery"]}]}""")),
        ToolDef("docs_get", "One cached doc item by id.", Json.parseToJsonElement("""{"type":"object","properties":{"id":{"type":"integer"}},"required":["id"]}""")),
    )
}

/** Exact name + version from a Cargo.lock body; first matching package wins. */
fun resolveLock(lockText: String, name: String): Pair<String, String>? {
    val lock = runCatching { Toml.parse(lockText) }.getOrNull() ?: return null
    val pkgs = (lock["package"] as? JsonArray) ?: if (lock.containsKey("package")) return null else JsonArray(emptyList())
    // serde: every [[package]] must carry string name + version, or the whole lock fails to parse.
    val parsed = pkgs.map { p ->
        val o = p as? JsonObject ?: return null
        val n = (o["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val v = (o["version"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        n to v
    }
    return parsed.firstOrNull { it.first == name }?.takeIf { safePart(it.first) && safePart(it.second) }
}

/** A crate name or version as crates.io allows them: both reach the docs.rs URL and `<home>/docs/<name>/<version>`. */
private fun safePart(s: String) = s.isNotEmpty() && !s.startsWith('.') && ".." !in s &&
    s.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "._+-" }

private fun lockPath() = "${cwd()}/Cargo.lock"
private fun missLock(name: String) = "not in Cargo.lock: $name"
private fun notCached(name: String) = "not cached: rtok docs fetch $name"

/** The canonical cwd after `local:`, so workspaces sharing one store never see each other's llms text. */
private fun localVersion(): String { val c = cwd(); return "local:${canonicalize(c) ?: c}" }

private fun readLock(): String? = runCatching { readToString(lockPath()) }.getOrNull()

private fun info(name: String, version: String, cached: Boolean) = buildJsonObject { put("name", name); put("version", version); put("cached", cached) }.toString()

fun docsResolve(rt: Runtime, name: String): String {
    runCatching { ingestLlms(rt) }
    if (name == "llms") return info("llms", "local", runCatching { rt.store.docCrateCached("llms", localVersion()) }.getOrDefault(false))
    val (n, ver) = readLock()?.let { resolveLock(it, name) } ?: return missLock(name)
    return info(n, ver, runCatching { rt.store.docCrateCached(n, ver) }.getOrDefault(false))
}

fun docsQuery(rt: Runtime, name: String, version: String?, query: String): String {
    runCatching { ingestLlms(rt) }
    val ver = when {
        !version.isNullOrEmpty() && !(name == "llms" && version == "local") -> version
        name == "llms" -> localVersion()
        else -> readLock()?.let { resolveLock(it, name) }?.second ?: return notCached(name)
    }
    if (!runCatching { rt.store.docCrateCached(name, ver) }.getOrDefault(false)) return notCached(name)
    val cfg = rt.config.plugins.docs
    val maxTokens = cfg.maxTokens.coerceAtLeast(1u)
    val hits = rt.store.searchDocItems(name, ver, query, cfg.queryLimit.coerceAtLeast(1u), cfg.snippetChars.coerceAtLeast(1u))
    // The saving's before side is the full text of everything considered, before snippet and budget trimming.
    val bodies = runCatching { rt.store.docItemsDocs(hits.map { it.id }) }.getOrDefault(emptyList())
    val kept = ArrayList<JsonObject>()
    for (h in hits) {
        val next = buildJsonObject { put("id", h.id); put("path", h.path); put("kind", h.kind); put("snippet", h.snippet) }
        if (rt.estimate(JsonArray(kept + next).toString(), Class.Prose) > maxTokens) break
        kept += next
    }
    val text = JsonArray(kept).toString()
    if (kept.isNotEmpty()) runCatching {
        rt.record(Measurement("docs", "query", bodies.sumOf { it.encodeToByteArray().size.toULong() }, text.encodeToByteArray().size.toULong(),
            rt.estimate(bodies.joinToString("\n"), Class.Prose), rt.estimate(text, Class.Prose), "$name@$ver", rt.callId))
    }
    return text
}

fun docsGet(rt: Runtime, id: Int): String {
    val item = rt.store.getDocItem(id) ?: throw IllegalArgumentException("unknown doc id: $id")
    return buildJsonObject { put("id", item.id); put("path", item.path); put("kind", item.kind); put("docs", item.docs) }.toString()
}

/** `llms-full.txt` / `llms.txt` split on headings into crate `llms` under [localVersion]. Unchanged bytes touch
 *  nothing (ids stay valid); a missing or empty source drops the workspace's cache. */
private fun ingestLlms(rt: Runtime) {
    val dir = cwd(); val version = localVersion()
    val body = listOf("llms-full.txt", "llms.txt").map { "$dir/$it" }.firstOrNull(::isFile)?.let(::readToString)
    val items = body?.let(::splitLlms).orEmpty()
    if (body == null || items.isEmpty()) {
        if (rt.store.docCrateCached("llms", version)) rt.store.deleteDocCrate("llms", version)
        return
    }
    rt.store.syncDocCrate("llms", version, hexSha256(body.encodeToByteArray()), 0, now(), items)
}

internal fun splitLlms(body: String): List<DocItemInput> {
    val items = ArrayList<DocItemInput>(); var heading = "llms"; val buf = StringBuilder()
    for (line in body.rustLines()) {
        if (line.startsWith('#')) {
            if (buf.isNotBlank()) items += Triple(heading, "llms", buf.trim().toString())
            heading = line.substring(1).trim().ifEmpty { "llms" }; buf.clear()
        } else buf.append(line).append('\n')
    }
    if (buf.isNotBlank()) items += Triple(heading, "llms", buf.trim().toString())
    return items
}

/** rustdoc JSON `paths` + `index`: path, kind and docs of every documented item. */
fun indexRustdoc(json: JsonElement): Pair<Int, List<DocItemInput>> {
    val o = json as? JsonObject
    val formatVersion = (o?.get("format_version") as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toULongOrNull()?.toInt() ?: 0
    val paths = o?.get("paths") as? JsonObject; val index = o?.get("index") as? JsonObject
    val items = ArrayList<DocItemInput>()
    paths?.forEach { (id, summary) ->
        val path = ((summary as? JsonObject)?.get("path") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            ?.joinToString("::")?.takeIf { it.isNotEmpty() } ?: id
        val kind = kindStr((summary as? JsonObject)?.get("kind") ?: JsonNull)
        val docs = (((index?.get(id) as? JsonObject)?.get("docs")) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""
        if (docs.isNotEmpty()) items += Triple(path, kind, docs)
    }
    return formatVersion to items
}

private fun kindStr(v: JsonElement): String = when {
    v is JsonPrimitive && v.isString -> v.content
    v is JsonObject && v.isNotEmpty() -> v.keys.first()
    else -> "item"
}

/** Fetch `name`'s rustdoc JSON from docs.rs through [get], decode it with [decode] (zstd) and cache it.
 *  A non-200 answer leaves the cache untouched. */
fun fetchCrate(rt: Runtime, name: String, get: (String) -> Pair<Int, ByteArray>, decode: (ByteArray) -> ByteArray): String {
    runCatching { ingestLlms(rt) }
    val (n, ver) = resolveLock(readLock() ?: "", name) ?: return missLock(name)
    val (status, bytes) = get("https://docs.rs/crate/$n/$ver/json.zst")
    if (status != 200) return "docs.rs returned ${statusLine(status)}"
    val sha = hexSha256(bytes)
    val json = Json.parseToJsonElement(decode(bytes).decodeToString())
    val (formatVersion, items) = indexRustdoc(json)
    val dir = "${rt.config.home}/docs/$n/$ver"; createDirAll(dir)
    writeBytes("$dir/rustdoc.json.zst", bytes); writeString("$dir/rustdoc.json.zst.sha256", "$sha\n")
    val count = rt.store.replaceDocCrate(n, ver, sha, formatVersion, now(), items)
    return "cached $n $ver ($count items)"
}

/** Rust prints the `u16` status code bare. */
private fun statusLine(code: Int) = code.toString()
