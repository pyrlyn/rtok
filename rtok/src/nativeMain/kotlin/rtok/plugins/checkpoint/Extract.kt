// Port of src/plugins/checkpoint.rs (extraction) — last 20 prompts, the touched files, the last 8 errors and
// the injected skills of a JSONL transcript, from bounded state so a huge transcript streams (T203).
package rtok.plugins.checkpoint

import kotlinx.serialization.json.*
import rtok.plugins.memory.redactPrivate
import rtok.sys.Shlex
import rtok.sys.compareUtf8
import rtok.sys.forEachFileLine
import rtok.sys.rustLines
import rtok.sys.takeScalars

private const val PROMPTS = 20
private const val ERRORS = 8
private const val ERROR_CHARS = 200
private const val PROMPT_CHARS = 300
private const val SKILL_DIR = "Base directory for this skill: "
private const val CREATED = "File created successfully"

/** From a transcript held in memory; [extractPath] is the streamed twin. */
fun extract(jsonl: String): Checkpoint = Extraction(true).apply { jsonl.rustLines().forEach(::line) }.finish()

/** Streamed from [path]; the empty checkpoint when it cannot be opened. */
fun extractPath(path: String): Checkpoint {
    val x = Extraction(true)
    return if (forEachFileLine(path) { it?.let(x::line) }) x.finish() else Checkpoint()
}

/** The shared loop; [filter] = [worthParsing] prefilter on (off only to prove it a superset). */
internal class Extraction(
    private val filter: Boolean,
) {
    private val prompts = ArrayDeque<String>()
    private val errors = ArrayDeque<String>()
    private val files = Files()
    private val skills = ArrayList<Pair<String, ULong>>()
    private var typed = 0uL
    private var skipped = 0uL

    fun line(line: String) {
        if (filter && !worthParsing(line)) return
        val v = parseStrict(line) ?: return
        walk(v, files.seen) { s ->
            if ("error" in s || "Error" in s || "ERROR" in s) {
                if (errors.size == ERRORS) errors.removeFirst()
                errors.addLast(redactPrivate(s).takeScalars(ERROR_CHARS))
            }
        }
        files.track(v)
        val skill = skillBody(v)
        if (skill != null) {
            skills += skill
            return
        }
        when (val u = userPrompt(v)) {
            is UserText.Typed -> {
                typed++
                if (prompts.size == PROMPTS) prompts.removeFirst()
                prompts.addLast(u.text)
            }

            UserText.Host -> {
                skipped++
            }

            null -> {}
        }
    }

    fun finish() = Checkpoint(prompts.toList(), files.entries(), errors.toList(), skills.toList(), typed = typed, skipped = skipped)
}

/** serde_json's `from_str`: kotlinx also accepts bare words, which Rust rejects. */
private fun parseStrict(line: String): JsonElement? {
    val v = runCatching { Json.parseToJsonElement(line) }.getOrNull() ?: return null
    if (v is JsonPrimitive && v.isString && !line.trimStart().startsWith('"')) return null
    return v
}

/** A raw line that cannot contribute anything; every marker is a safe superset of what the parse finds. */
internal fun worthParsing(line: String): Boolean {
    if ("file_path" in line || "error" in line || "Error" in line || "ERROR" in line || escapesALetter(line)) return true
    if (hasPair(line, "\"type\"", "\"create\"") || CREATED in line || (hasPair(line, "\"name\"", "\"Bash\"") && "rm" in line)) return true
    if (!hasPair(line, "\"type\"", "\"user\"")) return false
    if (hasPair(line, "\"isMeta\"", "true") && "\"sourceToolUseID\"" in line) return true
    return "\"tool_use_id\"" !in line || !hasPair(line, "\"type\"", "\"tool_result\"") || hasPair(line, "\"type\"", "\"text\"")
}

/** [key] then `:` and [value], JSON whitespace allowed around the colon. */
private fun hasPair(
    line: String,
    key: String,
    value: String,
): Boolean {
    var i = line.indexOf(key)
    while (i >= 0) {
        val rest = line.substring(i + key.length).trimStart(*JSON_WS)
        if (rest.startsWith(':') && rest.substring(1).trimStart(*JSON_WS).startsWith(value)) return true
        i = line.indexOf(key, i + 1)
    }
    return false
}

/** Rust `str::trim_start`: Unicode White_Space. */
private val JSON_WS =
    charArrayOf(
        ' ',
        '\t',
        '\n',
        '\r',
        '\u000B',
        '\u000C',
        '\u0085',
        '\u00A0',
        '\u1680',
        '\u2000',
        '\u2001',
        '\u2002',
        '\u2003',
        '\u2004',
        '\u2005',
        '\u2006',
        '\u2007',
        '\u2008',
        '\u2009',
        '\u200A',
        '\u2028',
        '\u2029',
        '\u202F',
        '\u205F',
        '\u3000',
    )

private fun escapesALetter(line: String): Boolean {
    var i = line.indexOf("\\u00")
    while (i >= 0) {
        if (line.getOrNull(i + 4) in '4'..'7') return true
        i = line.indexOf("\\u00", i + 1)
    }
    return false
}

private fun JsonElement?.str() = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonElement?.bool() = (this as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

private fun JsonElement?.u64() = (this as? JsonPrimitive)?.takeIf { !it.isString }?.content?.let { c -> c.takeIf { it.all { d -> d in '0'..'9' } }?.toULongOrNull() }

private fun JsonElement.at(vararg keys: String): JsonElement? = keys.fold(this as JsonElement?) { e, k -> (e as? JsonObject)?.get(k) }

private fun skillBody(v: JsonElement): Pair<String, ULong>? {
    if (v.at("type").str() != "user" || v.at("isMeta").bool() != true || v.at("sourceToolUseID") == null) return null
    val text = userText(v) ?: return null
    val first = text.rustLines().firstOrNull() ?: return null
    if (!first.startsWith(SKILL_DIR)) return null
    val dir = first.substring(SKILL_DIR.length).trimEnd('/', '\\')
    val name = dir.split('/', '\\').last().takeIf { it.isNotEmpty() } ?: return null
    return name to text.encodeToByteArray().size.toULong()
}

private sealed class UserText {
    data class Typed(
        val text: String,
    ) : UserText()

    data object Host : UserText()
}

private fun userPrompt(v: JsonElement): UserText? {
    if (v.at("type").str() != "user") return null
    val raw = userText(v) ?: return null
    if (raw.isBlank()) return null
    if (hostInjected(v)) return UserText.Host
    val t = stripReminders(redactPrivate(raw)).trim()
    return if (t.isEmpty() || HOST_OPENERS.any { t.startsWith(it) }) UserText.Host else UserText.Typed(t.takeScalars(PROMPT_CHARS))
}

private fun hostInjected(v: JsonElement): Boolean {
    if (v.at("isMeta").bool() == true || v.at("isCompactSummary").bool() == true) return true
    return v.at("origin", "kind").str()?.let { it != "human" } ?: false
}

private val HOST_OPENERS =
    listOf(
        "<task-notification>",
        "<ci-monitor-event>",
        "<local-command-stdout>",
        "<local-command-stderr>",
        "<local-command-caveat>",
        "<agent-message",
        "[Request interrupted by user",
    )

/** Drop every `<system-reminder>` block; an unclosed one runs to the end. */
internal fun stripReminders(text: String): String {
    val open = "<system-reminder>"
    val close = "</system-reminder>"
    val out = StringBuilder()
    var rest = text
    while (true) {
        val i = rest.indexOf(open)
        if (i < 0) break
        out.append(rest, 0, i)
        val j = rest.indexOf(close, i)
        rest = if (j < 0) "" else rest.substring(j + close.length)
    }
    return out.append(rest).toString()
}

private fun userText(v: JsonElement): String? =
    when (val c = v.at("message", "content")) {
        is JsonPrimitive -> c.str()
        is JsonArray -> c.mapNotNull { b -> if (b.at("type").str() != "text") null else b.at("text").str() }.joinToString("\n")
        else -> null
    }

private class Slot(
    var action: Action,
    var lines: Lines?,
)

/** Touched files by path, plus pending Write ids waiting for their result. */
private class Files {
    val seen = HashMap<String, Slot>()
    private val writes = HashMap<String, String>()

    fun track(v: JsonElement) {
        val blocks = v.at("message", "content") as? JsonArray ?: return
        val cwd = v.at("cwd").str()
        val created = blocks.size == 1 && v.at("toolUseResult", "type").str() == "create"
        for (b in blocks) {
            when (b.at("type").str()) {
                "tool_use" -> toolUse(b, cwd)
                "tool_result" -> toolResult(b, created)
            }
        }
    }

    private fun toolUse(
        b: JsonElement,
        cwd: String?,
    ) {
        val input = b.at("input")
        val path = input?.at("file_path").str()?.takeIf { it.isNotEmpty() }
        when (b.at("name").str()) {
            "Read" -> {
                if (path != null) {
                    fun n(k: String) = input?.at(k).u64()
                    val from = n("offset")
                    val limit = n("limit")
                    val lines = if (from != null || limit != null) (from ?: 1u).let { f -> Lines(f, limit?.let { l -> sat(f, if (l == 0uL) 0u else l - 1u) }) } else null
                    val slot = slot(path)
                    if (slot.action == Action.Read) slot.lines = lines
                }
            }

            "Edit", "MultiEdit" -> {
                if (path != null) mark(path, Action.Edited)
            }

            "Write" -> {
                if (path != null) {
                    mark(path, Action.Edited)
                    b.at("id").str()?.let { writes[it] = path }
                }
            }

            "Bash" -> {
                input?.at("command").str()?.let { c -> removedPaths(c, cwd).forEach { mark(it, Action.Deleted) } }
            }

            else -> {}
        }
    }

    private fun toolResult(
        b: JsonElement,
        created: Boolean,
    ) {
        val path = b.at("tool_use_id").str()?.let { writes.remove(it) } ?: return
        val said =
            when (val c = b.at("content")) {
                is JsonPrimitive -> c.str()?.startsWith(CREATED) == true
                is JsonArray -> c.any { it.at("text").str()?.startsWith(CREATED) == true }
                else -> false
            }
        if (created || said) mark(path, Action.Created)
    }

    private fun slot(path: String) = seen.getOrPut(path) { Slot(Action.Read, null) }

    private fun mark(
        path: String,
        action: Action,
    ) {
        val s = slot(path)
        if (!(s.action == Action.Created && action == Action.Edited)) s.action = action
        s.lines = null
    }

    fun entries(): List<PathEntry> =
        seen.entries
            .sortedWith { a, b -> compareUtf8(a.key, b.key) }
            .map { PathEntry(it.key, it.value.action, it.value.lines) }
            .sortedBy { it.action.ordinal }
}

/** Every `file_path` (as a read) and every string, through [text]. */
private fun walk(
    v: JsonElement,
    paths: HashMap<String, Slot>,
    text: (String) -> Unit,
) {
    when (v) {
        is JsonPrimitive -> {
            v.str()?.let(text)
        }

        is JsonObject -> {
            v["file_path"].str()?.takeIf { it.isNotEmpty() }?.let { paths.getOrPut(it) { Slot(Action.Read, null) } }
            v.values.forEach { walk(it, paths, text) }
        }

        is JsonArray -> {
            v.forEach { walk(it, paths, text) }
        }
    }
}

private val SEPARATORS = setOf("&&", "||", "|", "&")
private const val GLOB_CHARS = "*?[{\$`~!"

/** Files a Bash command removes with `rm` / `git rm`, read conservatively: globs, variables and,
 *  after a `cd`, relative paths are unknown; `git rm --cached` / `-n` keep the file. */
internal fun removedPaths(
    command: String,
    cwd: String?,
): List<String> {
    val words = Shlex.split(command) ?: return emptyList()
    val moved = words.any { it == "cd" || it == "pushd" }
    val out = ArrayList<String>()
    var i = 0
    var start = true
    while (i < words.size) {
        val (w, ends) = if (words[i].endsWith(';')) words[i].dropLast(1) to true else words[i] to (words[i] in SEPARATORS)
        val gitRm = start && w == "git" && words.getOrNull(i + 1) == "rm"
        if (start && (w == "rm" || gitRm) && !ends) {
            i += if (gitRm) 2 else 1
            val files = ArrayList<String>()
            var keeps = false
            var flags = true
            while (i < words.size) {
                val raw = words[i]
                val last = raw.endsWith(';')
                val a = if (last) raw.dropLast(1) else raw
                if (a in SEPARATORS) break
                if (flags && a == "--") {
                    flags = false
                } else if (flags && a.startsWith('-')) {
                    keeps = keeps || (gitRm && a in setOf("--cached", "-n", "--dry-run"))
                } else if (a.isNotEmpty() && a.none { it in GLOB_CHARS }) {
                    files += a
                }
                i++
                if (last) break
            }
            if (!keeps) files.filter { !moved || it.startsWith('/') }.forEach { out += resolve(cwd, it) }
            start = true
            continue
        }
        start = ends
        i++
    }
    return out
}

private fun resolve(
    cwd: String?,
    path: String,
): String {
    if (cwd == null || path.startsWith('/')) return path
    val sep = if ('/' in cwd) "/" else "\\"
    val out = cwd.trimEnd('/', '\\').split('/', '\\').toMutableList()
    for (c in path.split('/')) {
        when (c) {
            "", "." -> {}

            ".." -> {
                if (out.size > 1) out.removeLast()
            }

            else -> {
                out += c
            }
        }
    }
    return out.joinToString(sep)
}
