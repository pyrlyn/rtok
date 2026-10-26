// Port of src/measure/usage/gemini.rs: Gemini CLI's `tmp/<project>/chats/session-*.jsonl` (and the legacy `.json`).
// A message re-recorded under the same id is one request; `input` includes `cached`, thoughts join output.
package rtok.measure.usage

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import rtok.measure.HostSlices
import rtok.measure.Legs
import rtok.measure.UsageLogs
import rtok.measure.count
import rtok.measure.field
import rtok.measure.lineTs
import rtok.measure.parseJson
import rtok.measure.str
import rtok.store.UsageSlice
import rtok.sys.components
import rtok.sys.extension
import rtok.sys.fileName

internal object Gemini {
    fun slices(
        dirs: List<String>,
        since: Long,
        cutoff: Long,
    ): HostSlices = readEach(sessionFiles(dirs, cutoff, listOf("jsonl", "json"), ::isChat)) { p, text -> session(p, text, since) }

    private fun isChat(p: String) = fileName(p)?.startsWith("session-") == true && "chats" in components(p)

    private fun session(
        path: String,
        text: String,
        since: Long,
    ): List<UsageSlice>? {
        var id = stem(path)
        val messages = HashMap<String, JsonElement>()
        val values = if (extension(path) == "json") listOfNotNull(parseJson(text)) else text.lines().mapNotNull(::parseJson)
        for (v in values) {
            v.field("sessionId").str()?.let { id = it }
            for (m in ((v.field("messages") as? JsonArray).orEmpty() + v)) m.field("id").str()?.let { messages[it] = m }
        }
        if (values.isEmpty() && text.isNotBlank()) return null
        return messages.entries.sortedBy { it.key }.mapNotNull { request(id, it.value, since) }
    }

    private fun request(
        session: String,
        m: JsonElement,
        since: Long,
    ): UsageSlice? {
        val t = m.field("tokens")?.takeIf { m.field("type").str() == "gemini" } ?: return null
        val cached = t.count("cached")
        val legs = Legs((t.count("input") - cached).coerceAtLeast(0) + t.count("tool"), 0, cached, t.count("output") + t.count("thoughts"))
        return UsageLogs.slice("gemini", "google", session, m.field("model").str(), lineTs(m), legs, since)
    }
}
