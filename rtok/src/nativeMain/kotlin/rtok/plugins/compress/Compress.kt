// Port of src/plugins/compress/mod.rs — optional semantic shrink after the lossless `archive` lane (P28):
// in `proxy.mode = "compress"` an archived block's pointer becomes a deterministic extractive summary
// (or a stored model summary). The archive row is unchanged; `expand <id>` returns the original bytes.
package rtok.plugins.compress

import kotlinx.serialization.json.*
import rtok.plugin.sdk.*
import rtok.plugins.archive.clip
import rtok.plugins.archive.outsideLiveZone
import rtok.plugins.archive.utf8Len
import rtok.shortId
import rtok.sys.asciiLowercase
import rtok.sys.rustLines
import rtok.sys.takeScalars
import kotlin.native.concurrent.ThreadLocal
import rtok.config.Compress as CompressCfg
import rtok.config.Proxy as ProxyCfg

private const val ID = "compress"

/** The ids this thread's last filter pass found without a stored summary, waiting for the queue. */
@ThreadLocal private object Offered {
    val ids = ArrayList<String>()
}

fun takeOffered(): List<String> = Offered.ids.toList().also { Offered.ids.clear() }

object CompressPlugin : Plugin {
    override fun manifest() = Manifest(ID, listOf(Surface.Proxy), true)

    override fun dashboardPage() = DashboardPage("Compress", "Extractive summaries for archived tool output; expand returns the original.", true)

    override fun proxyFilter(
        req: WireRequest,
        cx: Ctx,
    ): List<Measurement> {
        if (!cfg(cx).enabled || cx.config("proxy", ProxyCfg.serializer(), ProxyCfg()).mode != "compress") return emptyList()
        return rewrite(req.toolResults(), cx)
    }
}

private fun cfg(cx: Ctx) = cx.pluginConfig(ID, CompressCfg.serializer(), CompressCfg())

fun rewrite(
    results: List<ToolResultRef>,
    cx: Ctx,
): List<Measurement> = outsideLiveZone(results, cx).mapNotNull { summarizeBlock(it, cx) }

private fun summarizeBlock(
    r: ToolResultRef,
    cx: Ctx,
): Measurement? {
    val pointer = (r.content as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    val d =
        runCatching { cx.archiveDecision(r.id) }.getOrElse {
            cx.log("error", "plugin", ID, "decision: ${it.message}")
            return null
        }
    if (d == null || d.expanded || !d.pointer.startsWith("[archived ") || pointer != d.pointer) return null
    val summary =
        storedView(d.archiveId, pointer.utf8Len(), cx) ?: run {
            val bytes =
                runCatching { cx.getArchive(d.archiveId) }.getOrElse {
                    cx.log("error", "plugin", ID, "get: ${it.message}")
                    return null
                }
                    ?: run {
                        cx.log("error", "plugin", ID, "missing archive payload")
                        return null
                    }
            extractiveSummary(d.archiveId, bytes.decodeToString())
        }
    if (summary.utf8Len() >= pointer.utf8Len()) return null
    r.content = JsonPrimitive(summary)
    return Measurement(ID, "summary", pointer.utf8Len(), summary.utf8Len(), cx.estimate(pointer, Class.Code), cx.estimate(summary, Class.Code), d.archiveId)
}

/** The stored model summary as the block the agent sees (T385.9.2); a missing one is offered to the queue. */
private fun storedView(
    archiveId: String,
    pointerLen: ULong,
    cx: Ctx,
): String? {
    val llm = cfg(cx).llm
    if (!llm.enabled) return null
    val summary =
        runCatching { cx.archiveSummary(archiveId, version(llm.model)) }.getOrElse {
            cx.log("error", "plugin", ID, "summary: ${it.message}")
            return null
        }
            ?: run {
                Offered.ids += archiveId
                return null
            }
    val short = shortId(archiveId)
    return "[compress $short]\ntype: tool_output\nsummary: $summary\nexpand($short)".takeIf { it.utf8Len() < pointerLen }
}

/** Deterministic extractive ranker — no network, no LLM (D12). Each source line at most once, clipped; errors first. */
fun extractiveSummary(
    id: String,
    text: String,
): String {
    val seen = HashSet<String>()
    val lines = text.rustLines().map { it.trimEnd() }.filter { it.isNotEmpty() && seen.add(it) }
    val title = lines.firstOrNull()?.takeScalars(80)
    val narrative = lines.drop(1).take(3).joinToString("; ") { it.takeScalars(60) }
    val rest = lines.drop(4)

    fun isError(l: String) = "error" in l.asciiLowercase()
    val facts = (rest.filter(::isError) + rest.filter { !isError(it) && ':' in it }).take(3)
    val files = rest.filter { it !in facts && ('/' in it || '\\' in it) }.take(3)
    val short = shortId(id)
    return buildString {
        append("[compress $short]\ntype: tool_output\ntitle: ${title ?: "tool result"}\nnarrative: $narrative")
        if (facts.isNotEmpty()) {
            append("\nfacts:")
            facts.forEach { append("\n- ").append(clip(it)) }
        }
        if (files.isNotEmpty()) {
            append("\nfiles:")
            files.forEach { append("\n- ").append(clip(it)) }
        }
        append("\nexpand($short)")
    }
}
