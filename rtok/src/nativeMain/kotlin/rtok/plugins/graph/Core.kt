// Port of the core types of src/plugins/graph/mod.rs: `Filter`, `Mode`, `backend_name`, `mode_of`, `index_for`,
// `pending_paths`, `with_stale`, `without_mode_line`, `lsp_none_answer`.
package rtok.plugins.graph

import rtok.config.Graph
import rtok.plugin.sdk.Ctx

/** The `[plugins.graph]` section of the running config; defaults off a non-runtime host. */
internal fun graphCfg(cx: Ctx): Graph = (cx.host as? rtok.plugin.Runtime)?.config?.plugins?.graph ?: Graph()

/**
 * T52.1: optional narrow-down for `symbol` / `callers` / `impact` (`path` substring, `kind` exact on `symbol`).
 * `impact` walks the full graph and filters the reported lines, so depth still crosses files outside the filter.
 */
data class Filter(
    val path: String = "",
    val kind: String = "",
    /** T377: `impact` prints every reached row instead of the budgeted, file-grouped answer. */
    val all: Boolean = false,
) {
    fun pathOk(path: String) = this.path.isEmpty() || path.contains(this.path)
    fun kindOk(kind: String) = this.kind.isEmpty() || kind == this.kind

    /** ` in <path>` / ` of kind <kind>` for empty answers; empty when unfiltered, so those stay byte-exact (T8.9). */
    fun scopeNote(): String = buildString {
        if (path.isNotEmpty()) append(" in $path")
        if (kind.isNotEmpty()) append(" of kind $kind")
    }

    companion object { val None = Filter() }
}

/** T329.9: how one project is answered. `Tags` is the default and never looks at a server. */
enum class Mode {
    Tags,
    /** Server first, tags when it cannot answer (T376); no mode header. */
    Lsp,
    /** Per project and language: server first, tags second, and the answer says which spoke. */
    Auto,
    /** T329.10: plain text search, no index and no server. */
    Text;

    companion object {
        /** A value the config schema refuses reads as `Tags`, the safe answer. */
        fun named(name: String): Mode = when (name) { "lsp" -> Lsp; "auto" -> Auto; "text" -> Text; else -> Tags }
    }
}

object GraphCore {
    /** The `backend` governing [root]: its language's `backend_by_language` entry, else `backend`. */
    fun backendName(cx: Ctx, root: String): String {
        val cfg = graphCfg(cx)
        // Only a config with overrides pays for the language lookup, so the default path stays stat-free.
        if (cfg.backendByLanguage.isEmpty()) return cfg.backend
        return Langs.languageOf(root)?.let(cfg.backendByLanguage::get) ?: cfg.backend
    }

    fun modeOf(cx: Ctx, root: String): Mode = Mode.named(backendName(cx, root))

    /** T8.15: `auto_index = true` walks on every call; `false` indexes an empty root once and never walks again. */
    fun indexFor(cx: Ctx, root: String): IndexReport = if (graphCfg(cx).autoIndex) Index.run(cx, root, false) else Index.ensure(cx, root)

    /** Pending re-index paths: hook marks, stat drift and the watcher queue (T68.3), sorted. */
    fun pendingPaths(cx: Ctx, root: String): List<String> {
        val key = Index.canon(root)
        return (cx.symbolPending(key, root) + cx.graphWatchPending(key)).toSet().sorted()
    }

    private fun staleBanner(cx: Ctx, root: String): String {
        val cfg = graphCfg(cx)
        val pending = pendingPaths(cx, root)
        if (pending.isEmpty()) return ""
        val watchPending = cx.graphWatchPending(Index.canon(root)).isNotEmpty() && cfg.watch != "off"
        if (!cfg.autoIndex || watchPending) {
            val tail = if (pending.size > 5) ", …" else ""
            return "stale: ${pending.size} files pending (${pending.take(5).joinToString(", ")}$tail)\n"
        }
        return ""
    }

    fun withStale(cx: Ctx, root: String, text: String): String = staleBanner(cx, root) + text

    /** The text after the line naming the mode (`(lsp)`, `(text)`, `(tags)`, `(tags; lsp: <reason>)`). */
    fun withoutModeLine(text: String): String =
        if (text.startsWith("(lsp)\n") || text.startsWith("(text)\n") || text.startsWith("(tags")) {
            text.indexOf('\n').let { if (it < 0) text else text.substring(it + 1) }
        } else text

    private val LSP_NONE = listOf("no definition of", "no references to", "nothing reaches", "no symbols resolved")

    /** An LSP answer saying the server found nothing; it is then checked against the tags index. */
    fun lspNoneAnswer(text: String): Boolean = text.trimStart().let { t -> t.isEmpty() || LSP_NONE.any(t::startsWith) }
}
