// Port of src/plugins/guard/skill.rs — T62.1: digest an oversized skill body before the host injects it;
// T392: remember each load per context window and warn on a repeat before a compaction.
package rtok.plugins.guard

import kotlinx.serialization.json.*
import rtok.config.envUserHome
import rtok.plugin.sdk.*
import rtok.plugins.read.markdownDigest
import rtok.shortId
import rtok.sys.isFile
import rtok.sys.readToString
import rtok.sys.rustLines

/** Frontmatter keys the host applies on invocation; a denied skill would lose them, so such skills load whole. */
private val HOST_KEYS = listOf("allowed-tools", "model", "context", "agent")

/** Read-cache namespace of the skill loads (T392); compaction clears it by prefix. */
private const val LOADS = "skill"

internal fun digest(ev: PreToolUse, cx: Ctx): PreToolDecision? {
    val g = guardCfg(cx)
    if (!g.skills) return null
    val name = skillName(ev.toolInput) ?: return null
    val home = envUserHome() ?: return null
    val path = resolve(name, cx.cwd(), home) ?: return null
    val body = runCatching { readToString(path) }.getOrNull() ?: return null
    return decide(cx, name, body, g.skillMaxBytes.toLong())
}

private fun skillName(input: JsonElement) =
    ((input as? JsonObject)?.get("skill") as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()

private fun loadKey(name: String, agent: String?) = if (!agent.isNullOrEmpty()) "$LOADS\t$name\t$agent" else "$LOADS\t$name"

/** `PostToolUse(Skill)`: remember the load; when the skill is already in this window, say so (context, not a deny). */
internal fun noteLoad(ev: PostToolUse, cx: Ctx): String? {
    val name = skillName(ev.toolInput)?.takeIf { it.isNotEmpty() } ?: return null
    val key = loadKey(name, cx.agentId())
    val earlier = runCatching { cx.getReadCache(key) }.getOrNull()
    runCatching { cx.putReadCache(key, LOADS, null) }
    val (_, ts) = earlier ?: return null
    val calls = runCatching { cx.callsSince(ts) }.getOrDefault(0L)
    return "rtok: skill $name was already loaded $calls calls ago in this context; use that copy instead of loading it again."
}

/** A compaction drops the loaded bodies, so the next load is a first one again. */
internal fun forgetLoads(cx: Ctx) { runCatching { cx.clearReadCache(LOADS) } }

/** Port of src/skill_path.rs. */
internal fun skillMdPath(home: String, name: String) = "$home/.claude/skills/$name/SKILL.md"

/** `SKILL.md` for a skill name: `plugin:skill` → that plugin's installPath; a bare name → project, then user. */
internal fun resolve(name: String, cwd: String?, home: String): String? {
    if (name.isEmpty() || name.contains('/') || name.contains('\\') || name.contains("..")) return null
    val colon = name.indexOf(':')
    if (colon >= 0) {
        val root = pluginRoot("$home/.claude/plugins/installed_plugins.json", name.substring(0, colon), cwd) ?: return null
        return "$root/skills/${name.substring(colon + 1)}/SKILL.md"
    }
    val project = cwd?.let { "$it/.claude/skills/$name/SKILL.md" }
    return if (project != null && isFile(project)) project else skillMdPath(home, name)
}

/** `plugins."<plugin>@<marketplace>"[i].installPath`; anything not provably the right install falls open. */
internal fun pluginRoot(manifest: String, plugin: String, cwd: String?): String? {
    val v = runCatching { Json.parseToJsonElement(readToString(manifest)) }.getOrNull() ?: return null
    val plugins = (v as? JsonObject)?.get("plugins") as? JsonObject ?: return null
    val keys = plugins.entries.filter { it.key.split('@').first() == plugin }
    if (keys.size != 1) return null
    val installs = keys[0].value as? JsonArray ?: return null
    fun s(i: JsonElement, k: String) = ((i as? JsonObject)?.get(k) as? JsonPrimitive)?.takeIf { it.isString }?.content
    fun only(pick: (JsonElement) -> Boolean) = installs.filter(pick).singleOrNull()
    val chosen = cwd?.let { c -> only { s(it, "projectPath") == c } }
        ?: only { s(it, "scope") == "user" }
        ?: installs.firstOrNull()?.takeIf { i ->
            installs.size == 1 && (i as? JsonObject)?.containsKey("scope") != true && (i as? JsonObject)?.containsKey("projectPath") != true
        }
        ?: return null
    return s(chosen, "installPath")
}

internal fun decide(cx: Ctx, name: String, body: String, cap: Long): PreToolDecision? {
    val bytes = body.encodeToByteArray().size.toLong()
    if (bytes <= cap || hostKeys(body)) return null
    val id = runCatching { cx.putArchive(body.encodeToByteArray()) }.getOrNull() ?: return null
    val lines = body.rustLines().size
    val map = truncateBytes(markdownDigest(body), (cap - 512).coerceAtLeast(0).toInt())
    val shown = shortId(id)
    val reason = "rtok kept the map of skill $name (${bytes / 1024} KB); the full body is archived, not loaded:\n$map" +
        "[rtok $shown · $lines lines · expand: rtok expand $shown]\n" +
        "Pull one section with `rtok expand $shown --grep <heading>`; set [plugins.guard] skills = false to load skills whole."
    runCatching {
        cx.record(Measurement("guard", "skill", bytes.toULong(), reason.encodeToByteArray().size.toULong(),
            cx.estimate(body, Class.Prose), cx.estimate(reason, Class.Prose), id))
    }
    return PreToolDecision.Deny(reason)
}

/**
 * True when the YAML frontmatter names a key the host applies. Frontmatter that is not a plain
 * string-keyed mapping is treated as exempt (fail open). See [frontmatterKeys] for the subset parsed.
 */
internal fun hostKeys(body: String): Boolean {
    if (!body.startsWith("---")) return false
    val rest = body.substring(3)
    val end = rest.indexOf("\n---").let { if (it < 0) rest.length else it }
    val yaml = rest.substring(0, end)
    if (yaml.isBlank()) return false
    val keys = frontmatterKeys(yaml) ?: return true
    return HOST_KEYS.any { it in keys }
}

/**
 * Top-level keys of a YAML mapping, or null when the text is not one (a list, a scalar, an unclosed
 * flow collection or quote, a duplicate key). Replaces Rust's serde_saphyr parse for this one check:
 * block mappings with plain/quoted keys and single-line flow mappings; nested lines are skipped.
 */
internal fun frontmatterKeys(yaml: String): Set<String>? {
    val text = yaml.replace("\r\n", "\n").replace('\r', '\n')
    val trimmed = text.trim()
    if (trimmed.startsWith("{")) {
        if (!trimmed.endsWith("}") || !balanced(trimmed)) return null
        val keys = LinkedHashSet<String>()
        for (item in splitTop(trimmed.substring(1, trimmed.length - 1))) {
            if (item.isBlank()) continue
            val k = keyOf(item.trim()) ?: return null
            if (!keys.add(k.first)) return null
        }
        return keys
    }
    val keys = LinkedHashSet<String>()
    for (line in text.split('\n')) {
        if (line.isBlank() || line.trimStart().startsWith('#')) continue
        if (line[0] == ' ' || line[0] == '\t') continue
        if (line == "-" || line.startsWith("- ")) return null
        val (k, value) = keyOf(line) ?: return null
        if (!balanced(value)) return null
        if (!keys.add(k)) return null
    }
    return keys
}

/** `key: value` with a plain, `"…"` or `'…'` key; null when there is no mapping colon. */
private fun keyOf(line: String): Pair<String, String>? {
    if (line.startsWith('"') || line.startsWith('\'')) {
        val q = line[0]; val close = line.indexOf(q, 1)
        if (close < 0) return null
        val after = line.substring(close + 1).trimStart()
        if (!after.startsWith(':')) return null
        return line.substring(1, close) to after.substring(1)
    }
    var i = 0
    while (i < line.length) {
        if (line[i] == ':' && (i + 1 == line.length || line[i + 1] == ' ' || line[i + 1] == '\t')) return line.substring(0, i).trim() to line.substring(i + 1)
        i++
    }
    return null
}

/** Flow brackets and quotes close on the same value. */
private fun balanced(v: String): Boolean {
    var depth = 0; var quote: Char? = null
    val t = v.trim()
    for (c in t) {
        val q = quote
        when {
            q != null -> if (c == q) quote = null
            (c == '"' || c == '\'') && (t.startsWith(c) || depth > 0) -> quote = c
            c == '[' || c == '{' -> depth++
            c == ']' || c == '}' -> { depth--; if (depth < 0) return false }
        }
    }
    return depth == 0 && quote == null
}

private fun splitTop(s: String): List<String> {
    val out = ArrayList<String>(); var depth = 0; var start = 0; var quote: Char? = null
    for ((i, c) in s.withIndex()) {
        val q = quote
        when {
            q != null -> if (c == q) quote = null
            c == '"' || c == '\'' -> quote = c
            c == '[' || c == '{' -> depth++
            c == ']' || c == '}' -> depth--
            c == ',' && depth == 0 -> { out += s.substring(start, i); start = i + 1 }
        }
    }
    out += s.substring(start)
    return out
}

/** Cut at a UTF-8 char boundary within [cap] bytes, with an ellipsis. */
internal fun truncateBytes(s: String, cap: Int): String {
    val b = s.encodeToByteArray()
    if (b.size <= cap) return s
    var end = cap
    while (end > 0 && (b[end].toInt() and 0xC0) == 0x80) end--
    return b.decodeToString(0, end) + "…\n"
}
