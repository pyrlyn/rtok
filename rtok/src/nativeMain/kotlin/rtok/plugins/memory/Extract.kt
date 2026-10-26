// Port of src/plugins/memory/extract.rs — opt-in automatic memory extraction (T666.1). rtok ships no
// model client: the one extractor runs the program named in `[plugins.memory.extract] command`.
package rtok.plugins.memory

import kotlinx.serialization.json.*
import rtok.plugin.Runtime
import rtok.sys.*

/** A note the extractor proposes; the kind is always `note` (model output never picks one). */
data class Candidate(val title: String, val body: String)

/** Turns scrubbed transcript text into candidate notes (at most [maxNotes] are kept). */
fun interface Extractor { fun extract(transcript: String, maxNotes: Int): List<Candidate> }

private const val MAX_TITLE = 120
internal const val MAX_BODY = 2000
/** A model call can be slow, but never unbounded. */
private const val RUN_LIMIT_MS = 600_000L

/** Runs argv with the transcript on stdin; one `{"title","body"}` object per stdout line comes back. */
class CommandExtractor(private val argv: List<String>) : Extractor {
    init {
        require(argv.firstOrNull()?.isNotBlank() == true) { "no extractor: set [plugins.memory.extract] command to a program that reads the transcript on stdin" }
    }

    override fun extract(transcript: String, maxNotes: Int): List<Candidate> {
        // The transcript goes in as a file: a file cannot deadlock against a full stdout pipe.
        val input = "${tempDir()}/rtok-extract-${pid()}-${kotlin.time.TimeSource.Monotonic.markNow().hashCode()}-${counter++}.txt"
        writeString(input, transcript)
        try {
            val (out, _) = try {
                ProgressRunner("", argv[0], argv.drop(1)).quiet().stdinFile(input).timeout(RUN_LIMIT_MS).runCapped(Long.MAX_VALUE)
            } catch (e: Exception) { throw IllegalStateException("running the extractor `${argv[0]}`: ${e.message}", e) }
            check(out.exitCode == 0) { "the extractor `${argv[0]}` exited with ${out.exitCode?.let { "exit status: $it" } ?: "signal"}" }
            return parseCandidates(out.stdout.decodeToString(), maxNotes)
        } finally { runCatching { removeFile(input) } }
    }

    private companion object { var counter = 0 }
}

/** Candidates from JSONL: bad lines and blank fields dropped, sizes and count capped. */
fun parseCandidates(out: String, maxNotes: Int): List<Candidate> = out.lines().mapNotNull { l ->
    val o = runCatching { Json.parseToJsonElement(l.trim()) }.getOrNull() as? JsonObject ?: return@mapNotNull null
    fun f(k: String): String? = when (val v = o[k]) { null -> ""; is JsonPrimitive -> if (v.isString) v.content else null; else -> null }
    val t = f("title") ?: return@mapNotNull null; val b = f("body") ?: return@mapNotNull null
    Candidate(t.trim().scalars().take(MAX_TITLE).joinToString(""), b.trim().scalars().take(MAX_BODY).joinToString(""))
}.filter { it.title.isNotEmpty() && it.body.isNotEmpty() }.take(maxNotes)

/** The `user:`/`assistant:` text of a Claude Code transcript; tool calls and results left out. */
fun transcriptText(jsonl: String): String = buildString {
    for (line in jsonl.lines()) {
        val v = runCatching { Json.parseToJsonElement(line) }.getOrNull() ?: continue
        val role = ((v as? JsonObject)?.get("type") as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""
        if (role != "user" && role != "assistant") continue
        val content = ((v as JsonObject)["message"] as? JsonObject)?.get("content")
        val text = when {
            content is JsonPrimitive && content.isString -> content.content
            content is JsonArray -> content.filter { b -> ((b as? JsonObject)?.get("type") as? JsonPrimitive)?.let { it.isString && it.content == "text" } == true }
                .mapNotNull { b -> ((b as JsonObject)["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content }.joinToString("\n")
            else -> ""
        }
        if (text.isNotBlank()) append(role).append(": ").append(text.trim()).append('\n')
    }
}

/** The last [max] bytes of [s], cut on a char boundary. */
fun tailBytes(s: String, max: Int): String {
    val b = s.encodeToByteArray(); if (b.size <= max) return s
    var start = b.size - max
    while (start < b.size && (b[start].toInt() and 0xC0) == 0x80) start++
    return b.decodeToString(start, b.size)
}

private const val OFF = "memory extraction is off: set [plugins.memory.extract] enabled = true (it can cost money)"

/** `rtok memory extract` with the configured command; the off switch is checked first. */
fun runConfiguredExtract(rt: Runtime, transcript: String): String {
    val cfg = rt.config.plugins.memory.extract
    check(cfg.enabled) { OFF }
    return runExtract(rt, CommandExtractor(cfg.command), transcript)
}

/** Saves what [extractor] proposes from [transcript]; refuses while disabled. */
fun runExtract(rt: Runtime, extractor: Extractor, transcript: String): String {
    val cfg = rt.config.plugins.memory.extract
    check(cfg.enabled) { OFF }
    val raw = try { readToString(transcript) } catch (e: IoException) { throw IoException(e.kind, "reading $transcript: ${e.message}") }
    val input = tailBytes(stripPrivate(transcriptText(raw)), cfg.maxInputBytes.toInt())
    if (input.isBlank()) return "nothing to extract: the transcript has no user or assistant text"
    val found = extractor.extract(input, cfg.maxNotes.toInt())
    var saved = 0; var skipped = 0
    // The gated save is the door MCP `mem_save` uses, so a repeat is refused here too.
    for (c in found.take(cfg.maxNotes.toInt())) when (runCatching { memSaveGated(rt, "note", c.title, c.body, null) }.getOrNull()) {
        is Saved.Stored -> saved++
        else -> skipped++
    }
    return "extracted ${found.size} candidates: saved $saved, skipped $skipped"
}
