// Port of crates/rtok-config/src/validate.rs — `rtok config validate` / `config set`:
// schema walk with file:line locations, value checks, pinned-default notes, and in-place edits
// that keep comments and layout (toml_edit replacement: a line-based editor).
package rtok.config

import kotlinx.serialization.json.*
import rtok.sys.ChangePreview
import rtok.sys.exists
import rtok.sys.readToString

object Validate {
    private val GRAPH_BACKENDS = listOf("tags", "lsp", "auto", "text")
    private val GRAPH_GRAMMARS = listOf("rust", "ts", "tsx", "js", "mjs", "cjs", "py", "dart", "c", "h", "go")
    private val CHOICES = mapOf(
        "log.tspin" to listOf("auto", "always", "off"), "plugins.graph.map_rank" to listOf("refs", "pagerank"),
        "plugins.graph.backend" to GRAPH_BACKENDS, "proxy.flex.on_429" to listOf("none", "backoff", "default"),
        "plugins.proxy.semantic_cache.embed_backend" to listOf("hash"),
    )
    private val UNIT_RATIO_KEYS = listOf("plugins.proxy.semantic_cache.threshold", "plugins.read.delta_max_ratio")
    const val TASK_PREFIX_MAX = 8

    /** Schema = default config tree; `setup.source` (an always-serialized Option in Rust) is a known string. */
    private val schema: JsonObject by lazy { insertDotted(Config().toJson(), "setup.source", JsonNull) }

    fun issues(path: String): List<String> = issuesIn(path, readToString(path))

    fun issuesIn(path: String, text: String): List<String> {
        val doc = try { Toml.parseDoc(text) } catch (e: TomlException) { return listOf("$path:${e.message}") }
        val errors = ArrayList<String>()
        checkTable(path, doc, "", doc.root, schema, errors)
        checkGraphMaps(path, doc, errors)
        if (errors.isEmpty()) checkTyped(path, doc, errors)
        return errors
    }

    private fun loc(path: String, doc: TomlDoc, dotted: String) = "$path:${doc.lineOf(dotted)}"

    private fun checkTyped(path: String, doc: TomlDoc, errors: MutableList<String>) {
        try { decodeConfig(deepMerge(Config().toJson(), doc.root)) } catch (e: ConfigException) {
            val key = Regex("""key "([^"]+)"""").find(e.message ?: "")?.groupValues?.get(1)
                ?: Regex("""\$\.([A-Za-z0-9_.\-]+)""").find(e.message ?: "")?.groupValues?.get(1)
            val msg = (e.message ?: "").lineSequence().first()
            errors += if (key == null) "$path:1: $msg" else "${loc(path, doc, key)}: $key: $msg"
        }
    }

    private fun checkGraphMaps(path: String, doc: TomlDoc, errors: MutableList<String>) {
        val graph = (doc.root["plugins"] as? JsonObject)?.get("graph") as? JsonObject ?: return
        for ((map, allowed) in listOf("extensions" to GRAPH_GRAMMARS, "backend_by_language" to GRAPH_BACKENDS)) {
            val t = graph[map] as? JsonObject ?: continue
            for ((k, item) in t) {
                val dotted = "plugins.graph.$map.$k"; val at = loc(path, doc, dotted)
                val s = (item as? JsonPrimitive)?.takeIf { it.isString }?.content
                when {
                    s == null -> errors += "$at: $dotted: expected string"
                    s !in allowed -> errors += "$at: $dotted must be one of ${allowed.joinToString(", ")} (got $s)"
                }
            }
        }
    }

    private fun isOpen(d: String) = d == "bench.configs" || d == "stats.prices" || d == "setup.paths" ||
        d == "plugins.graph.extensions" || d == "plugins.graph.backend_by_language" ||
        d.startsWith("plugins.graph.backend_by_language.") || d.startsWith("plugins.graph.extensions.")

    private fun checkTable(path: String, doc: TomlDoc, prefix: String, table: JsonObject, schema: JsonObject, errors: MutableList<String>) {
        for ((k, item) in table) {
            val dotted = if (prefix.isEmpty()) k else "$prefix.$k"
            if (isOpen(prefix) || isOpen(dotted)) continue
            if (!schema.containsKey(k)) { errors += "${loc(path, doc, dotted)}: unknown key: $dotted"; continue }
            val expected = schema[k]!!
            if (expected is JsonObject) {
                if (item is JsonObject) checkTable(path, doc, dotted, item, expected, errors) else errors += "${loc(path, doc, dotted)}: $dotted: expected table"
            } else checkLeaf(path, doc, dotted, item, expected, errors)
        }
    }

    private fun JsonElement.str() = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonElement.int() = (this as? JsonPrimitive)?.takeIf { Toml.isInteger(it) }?.longOrNull
    private fun JsonElement.flt() = (this as? JsonPrimitive)?.takeIf { Toml.isFloat(it) }?.doubleOrNull

    private fun checkLeaf(path: String, doc: TomlDoc, dotted: String, item: JsonElement, expected: JsonElement, errors: MutableList<String>) {
        val at = loc(path, doc, dotted)
        if (dotted == "agents.junk.extra") return checkJunkExtra(at, item, errors)
        if (expected is JsonPrimitive && expected.isString) {
            val v = item.str() ?: run { errors += "$at: $dotted: expected string"; return }
            CHOICES[dotted]?.let { if (v !in it) errors += "$at: $dotted: expected one of ${it.joinToString(" | ")}" }
        } else if (expected is JsonPrimitive && Toml.isBool(expected)) {
            if ((item as? JsonPrimitive)?.let { Toml.isBool(it) } != true) { errors += "$at: $dotted: expected bool"; return }
        } else if (expected is JsonPrimitive && expected !is JsonNull) {
            val isFloat = !Toml.isInteger(expected)
            val ok = if (isFloat) item.int() != null || item.flt() != null else item.int() != null
            if (!ok) { errors += "$at: $dotted: expected number"; return }
            if (!isFloat && (item.int() ?: 0) < 0) { errors += "$at: $dotted must be ≥ 0"; return }
        } else if (expected is JsonArray) {
            if (item !is JsonArray) { errors += "$at: $dotted: expected array"; return }
        }
        if (dotted in UNIT_RATIO_KEYS) {
            val x = item.flt() ?: item.int()?.toDouble()
            if (x != null && !(x > 0.0 && x <= 1.0)) errors += "$at: $dotted must be in (0, 1]"
        }
        item.int()?.let { n ->
            when {
                (dotted == "proxy.port" || dotted == "web.port") && n !in 1..65535 -> errors += "$at: $dotted out of range (1–65535)"
                dotted == "plugins.archive.keep_turns" && n < 1 -> errors += "$at: $dotted must be ≥ 1"
                dotted == "proxy.flex.retries" && n > 5 -> errors += "$at: $dotted must be ≤ 5"
                dotted in listOf("plugins.graph.live_heat_window_s", "plugins.graph.live_max_events_per_s", "plugins.graph.live_feed_rows") && n < 1 -> errors += "$at: $dotted must be ≥ 1"
                dotted == "plugins.graph.live_feed_rows" && n > 1000 -> errors += "$at: $dotted must be ≤ 1000"
                dotted == "tui.tick_secs" && n < 1 -> errors += "$at: $dotted must be ≥ 1"
                dotted == "plugins.read.max_chars" && n < 100 -> errors += "$at: $dotted must be ≥ 100"
                dotted == "log.max_bytes" && n < 1024 -> errors += "$at: $dotted must be ≥ 1024"
                dotted == "log.files" && n > 20 -> errors += "$at: $dotted must be ≤ 20"
                dotted in listOf("agents.junk.keep_logs_days", "agents.junk.stale_session_days", "agents.junk.stale_worktree_days", "agents.junk.crash_dump_min_age_days") && n > 3650 -> errors += "$at: $dotted must be ≤ 3650"
                dotted == "agents.junk.temp_min_age_hours" && n > 87_600 -> errors += "$at: $dotted must be ≤ 87600"
                else -> {}
            }
        }
        if (dotted == "agents.junk.exclude" && item is JsonArray) item.forEachIndexed { i, g ->
            val s = g.str()
            if (s == null) errors += "$at: $dotted[$i]: expected string" else Glob.error(s)?.let { errors += "$at: $dotted[$i]: $it" }
        }
        item.str()?.let { s ->
            when {
                dotted == "proxy.mode" && s != "passthrough" && s != "compress" -> errors += "$at: $dotted must be passthrough or compress"
                dotted == "plugins.read.default_mode" && s !in listOf("full", "lines", "map", "signatures") -> errors += "$at: $dotted must be full, lines, map, or signatures"
                dotted == "plugins.graph.watch" && s != "off" && s != "notify" -> errors += "$at: $dotted must be off or notify"
                dotted == "tasks.adapter" && s !in TASK_ADAPTERS -> errors += "$at: $dotted must be one of ${TASK_ADAPTERS.joinToString(", ")}"
                dotted == "tasks.disk.layout" && s !in TASK_LAYOUTS -> errors += "$at: $dotted must be one of ${TASK_LAYOUTS.joinToString(", ")}"
                dotted == "tasks.prefix" && s.isNotEmpty() -> checkTaskPrefix(s)?.let { errors += "$at: $dotted: $it" }
                dotted == "stats.since" || dotted == "report.since" -> try { parseSinceFrom(s, dotted) } catch (e: ConfigException) { errors += "$at: ${e.message}" }
                dotted == "log.level" && s.lowercase() !in listOf("error", "warn", "info", "debug") -> errors += "$at: $dotted must be error, warn, info, or debug"
                ((dotted.startsWith("proxy.lanes.") && dotted.endsWith(".upstream")) || dotted == "plugins.compress.llm.url") &&
                    s.isNotBlank() && !Regex("""(?i)https?://[^\s/?#]+[^\s]*""").matches(s.trim()) -> errors += "$at: $dotted must be empty or an http(s) URL"
                else -> {}
            }
        }
    }

    /** rtok-plugin-sdk `check_task_prefix`: 1 to 8 ASCII letters. */
    fun checkTaskPrefix(p: String): String? =
        if (p.isEmpty() || p.length > TASK_PREFIX_MAX || !p.all { it in 'a'..'z' || it in 'A'..'Z' }) "task prefix \"$p\" must be 1 to $TASK_PREFIX_MAX ASCII letters" else null

    private fun checkJunkExtra(at: String, item: JsonElement, errors: MutableList<String>) {
        val d = "agents.junk.extra"
        if (item !is JsonArray) { errors += "$at: $d: expected array"; return }
        item.forEachIndexed { i, e ->
            val key = "$d[$i]"
            val t = e as? JsonObject ?: run { errors += "$at: $key: expected table"; return@forEachIndexed }
            for (k in t.keys) if (k !in listOf("host", "kind", "path")) errors += "$at: unknown key: $key.$k"
            fun f(k: String) = t[k]?.str()
            val h = f("host"); if (!(h == "rtok" || h in HOSTS)) errors += "$at: $key.host must be rtok or a host id (see `rtok agents list`)"
            if (f("kind") !in JUNK_EXTRA_KINDS) errors += "$at: $key.kind must be one of ${JUNK_EXTRA_KINDS.joinToString(", ")}"
            if (f("path").isNullOrEmpty()) errors += "$at: $key.path must be a non-empty path"
        }
    }

    // ---- pinned defaults ----

    fun pinnedNotes(path: String): List<String> = runCatching { pinnedNotesIn(path, readToString(path)) }.getOrDefault(emptyList())

    fun pinnedNotesIn(path: String, text: String): List<String> {
        val doc = try { Toml.parseDoc(text) } catch (_: TomlException) { return emptyList() }
        val notes = ArrayList<String>(); noteTable(path, doc, "", doc.root, Config().toJson(), notes); return notes
    }

    private fun noteTable(path: String, doc: TomlDoc, prefix: String, table: JsonObject, schema: JsonObject, notes: MutableList<String>) {
        for ((k, item) in table) {
            val dotted = if (prefix.isEmpty()) k else "$prefix.$k"
            val expected = schema[k] ?: continue
            if (expected is JsonObject) { if (item is JsonObject) noteTable(path, doc, dotted, item, expected, notes) }
            else if (!sameLeaf(item, expected)) notes += "note ${loc(path, doc, dotted)}: $dotted = ${shown(item)} (default ${Layers.display(expected)})"
        }
    }

    private fun sameLeaf(item: JsonElement, expected: JsonElement): Boolean {
        if (item is JsonObject) return false
        return valueMatches(item, expected) || shown(item) == Layers.display(expected)
    }

    private fun valueMatches(v: JsonElement, e: JsonElement): Boolean = when {
        e is JsonArray -> v is JsonArray && v.size == e.size && v.zip(e).all { (a, b) -> valueMatches(a, b) }
        e !is JsonPrimitive || e is JsonNull -> false
        e.isString -> v.str() == e.content
        Toml.isBool(e) -> (v as? JsonPrimitive)?.takeIf { Toml.isBool(it) }?.boolean == e.boolean
        else -> {
            val i = v.int(); val f = v.flt(); val ed = e.double
            when {
                i != null -> if (Toml.isInteger(e)) i == e.long else fEq(i.toDouble(), ed)
                f != null -> !Toml.isInteger(e) && fEq(f, ed)
                else -> false
            }
        }
    }

    private fun fEq(a: Double, b: Double) = a == b || a.toString() == b.toString() || a.toFloat() == b.toFloat()

    private fun shown(v: JsonElement): String = when {
        v is JsonArray -> "[" + v.joinToString(", ") { shown(it) } + "]"
        v is JsonPrimitive && v.isString -> "\"${v.content}\""
        v is JsonPrimitive -> v.content
        else -> v.toString()
    }

    // ---- layered (dotenv/env/project/flag) values ----

    fun layeredIssues(values: List<Triple<String, JsonElement, String>>): List<String> {
        val docs = LinkedHashMap<String, JsonObject>()
        for ((key, value, source) in values) {
            if (source == "default" || source == "user") continue
            docs[source] = insertDotted(docs[source] ?: JsonObject(emptyMap()), key, value)
        }
        val out = ArrayList<String>()
        for ((source, doc) in docs.entries.sortedBy { it.key }.map { it.key to it.value }) for (e in issuesIn(source, Toml.write(doc))) {
            val rest = e.removePrefix("$source:").takeIf { it !== e && e.startsWith("$source:") }
            val msg = rest?.substringAfter(": ", missingDelimiterValue = "")?.takeIf { rest.contains(": ") } ?: e
            out += "$source: $msg"
        }
        return out
    }

    // ---- hooks ----
    private var rules: ((String, String) -> List<String>)? = null
    private var diff: ((String, String, String) -> String)? = null
    fun registerFileDiff(paint: (String, String, String) -> String) { diff = paint }
    fun fileDiff(path: String, before: String, after: String) = diff?.invoke(path, before, after) ?: ChangePreview.unifiedDiff(path, before, after)
    fun registerRules(check: (String, String) -> List<String>) { rules = check }
    fun rulesIssues(rulesPath: String, rulesDir: String) = rules?.invoke(rulesPath, rulesDir) ?: emptyList()

    // ---- config set ----

    fun set(home: String, key: String, raw: String, dryRun: Boolean) = setAllWith(home, null, listOf(key to raw), dryRun)
    fun setWith(home: String, configFile: String?, key: String, raw: String, dryRun: Boolean) = setAllWith(home, configFile, listOf(key to raw), dryRun)

    /** `rtok config set`: re-encodes the whole file via the TOML backend — comments are dropped (differs from Rust's toml_edit). */
    fun setAllWith(home: String, configFile: String?, pairs: List<Pair<String, String>>, dryRun: Boolean): Pair<String, String> {
        for ((k, _) in pairs) if (k.isEmpty() || k.split('.').any { it.isEmpty() }) throw ConfigException("empty key")
        val path = Config.userPath(home, configFile)
        if (!exists(path)) {
            if (dryRun) throw ConfigException("no config file yet; run `rtok config init` first")
            Config.initMaybe(home, configFile, false, false)
        }
        val original = readToString(path)
        var tree = try { Toml.parse(original) } catch (e: TomlException) { throw ConfigException("$path: ${e.message}") }
        for ((k, raw) in pairs) { requireTablePath(tree, k); requireTablePath(defaultsTree, k); tree = insertDotted(tree, k, parseValue(raw)) }
        val source = Toml.write(tree)
        val errs = issuesIn(path, source)
        if (errs.isNotEmpty()) throw ConfigException(errs.joinToString("\n"))
        val d = fileDiff(path, original, source)
        if (!dryRun) writeFile(path, source)
        return path to d
    }

    private val defaultsTree by lazy { Config().toJson() }

    /** Every parent of [dotted] that already exists must be a table. */
    private fun requireTablePath(tree: JsonObject, dotted: String) {
        val parts = dotted.split('.'); var cur: JsonElement? = tree
        for (i in 0 until parts.size - 1) {
            cur = (cur as? JsonObject)?.get(parts[i]) ?: return
            if (cur !is JsonObject) throw ConfigException("${parts.subList(0, i + 1).joinToString(".")} is not a table; cannot set $dotted")
        }
    }

    /** `raw` as a TOML value when it parses as one, else as a string. */
    internal fun parseValue(raw: String): JsonElement =
        try { Toml.parse("v = $raw\n")["v"] ?: JsonPrimitive(raw) } catch (_: TomlException) { JsonPrimitive(raw) }
}

/** globset-compatible syntax check (unclosed classes/alternates, nested alternates). */
object Glob {
    fun error(p: String): String? {
        var i = 0; var brace = 0
        while (i < p.length) {
            when (p[i]) {
                '\\' -> i++
                '[' -> { val end = p.indexOf(']', i + 2); if (end < 0) return "error parsing glob '$p': unclosed character class; missing ']'"; i = end }
                '{' -> { if (brace > 0) return "error parsing glob '$p': nested alternate groups are not allowed"; brace++ }
                '}' -> { if (brace == 0) return "error parsing glob '$p': unopened alternate group; missing '{' (maybe escape '}' with '[}]'?)"; brace-- }
            }
            i++
        }
        return if (brace > 0) "error parsing glob '$p': unclosed alternate group; missing '}' (maybe escape '{' with '[{]'?)" else null
    }
}
