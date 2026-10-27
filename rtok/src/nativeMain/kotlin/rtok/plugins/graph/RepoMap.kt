// Port of src/plugins/graph/mod.rs `repo_map` (T52.3, T370): the SessionStart repo map.
package rtok.plugins.graph

import rtok.plugin.sdk.Class
import rtok.plugin.sdk.Ctx
import rtok.plugin.sdk.Injection
import rtok.plugin.sdk.SessionStart
import rtok.plugins.checkpoint.lastPaths

internal object RepoMap {
    private const val NANOS = 1_000_000_000L

    private fun nowNanos(): Long =
        kotlin.time.Clock.System
            .now()
            .let { it.epochSeconds * NANOS + it.nanosecondsOfSecond }

    /**
     * Ranked repo map from existing `symbols` rows. `map_tokens = 0` is off; a missing index does not walk
     * the tree (hook path). `map_rank = "pagerank"` (T370) reads the stored file graph instead and falls back
     * to the reference counts when none is stored.
     */
    @Suppress("ReturnCount")
    fun inject(
        ev: SessionStart,
        cx: Ctx,
    ): Injection? {
        val cfg = graphCfg(cx)
        val cap = cfg.mapTokens
        if (cap == 0u) return null
        val cwd = cx.cwd() ?: runCatching { rtok.sys.cwd() }.getOrNull() ?: return null
        val root = Index.canon(cwd)
        if (cfg.mapRank == "pagerank") {
            val seeds = if (ev.source == "compact") lastPaths(cx, root) else emptyList()
            Rank.map(cx, root, cap, seeds, nowNanos())?.let { return Injection("graph", it, 1u) }
        }
        val rows = runCatching { cx.symbolTopRefs(root, cap.toLong()) }.getOrNull() ?: return null
        if (rows.isEmpty()) return null
        val lines = mutableListOf("repo map")
        for (r in rows) lines += "${r.name} ${r.path}:${r.line} ${r.refs}"
        var text = lines.joinToString("\n")
        while (cx.estimate(text, Class.Prose) > cap && lines.size > 1) {
            lines.removeAt(lines.lastIndex)
            text = lines.joinToString("\n")
        }
        if (lines.size == 1) return null
        return Injection("graph", text, 1u)
    }
}
