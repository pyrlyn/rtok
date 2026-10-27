// Port of src/model/mod.rs `PluginPage`, `Model::plugins`, `plugin_stat_totals` and `config_fields`: the Plugins page.
package rtok.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import rtok.config.Config
import rtok.plugin.pluginStateKey
import rtok.plugins.Registry
import rtok.plugins.checkpoint.promptCounts
import rtok.store.Store
import rtok.store.kvPrefix
import rtok.store.measurementTotals
import rtok.store.memoryNoteAggs

/** One plugin's page: manifest, static copy, live and config fields, and stats when it saves tokens. */
data class PluginPage(
    val id: String,
    val enabled: Boolean,
    val surfaces: List<String>,
    val title: String,
    val summary: String,
    val savesTokens: Boolean,
    val fields: List<Pair<String, String>>,
    val stats: Stats?,
) {
    fun toJson(): JsonObject =
        JsonObject(
            mapOf(
                "id" to JsonPrimitive(id),
                "enabled" to JsonPrimitive(enabled),
                "surfaces" to JsonArray(surfaces.map(::JsonPrimitive)),
                "title" to JsonPrimitive(title),
                "summary" to JsonPrimitive(summary),
                "saves_tokens" to JsonPrimitive(savesTokens),
                // A Rust `(String, String)` serializes as a two-element array.
                "fields" to JsonArray(fields.map { (k, v) -> JsonArray(listOf(JsonPrimitive(k), JsonPrimitive(v))) }),
                "stats" to (stats?.let { Json.encodeToJsonElement(it) } ?: JsonNull),
            ),
        )
}

object Plugins {
    /** The catalogue, each with its page and, when it saves tokens, its stats from one grouped read (T207). */
    fun build(
        cfg: Config,
        store: Store?,
    ): List<PluginPage> {
        val totals = statTotals(store)
        return Registry.new(cfg).pages().map { (m, enabled, page) ->
            val fields = page.fields.toMutableList()
            if (m.id == "memory" && store != null) {
                runCatching { store.memoryNoteAggs(null) }.getOrNull()?.let { aggs ->
                    fields += "notes live" to aggs.sumOf { it.live }.toString()
                    fields += "notes pinned" to aggs.sumOf { it.pinned }.toString()
                    fields += "notes retired" to aggs.sumOf { it.retired }.toString()
                }
                runCatching { store.kvPrefix(pluginStateKey("memory", "")) }.getOrNull()?.let { rows ->
                    val (typed, skipped) = promptCounts(rows)
                    fields += "checkpoint prompts typed" to typed.toString()
                    fields += "checkpoint host records skipped" to skipped.toString()
                }
            }
            // After the live numbers: the TUI shows these pairs on one line (T419).
            fields += configFields(m.id, cfg)
            PluginPage(
                m.id,
                enabled,
                m.surfaces.map { it.asStr() },
                page.title,
                page.summary,
                page.savesTokens,
                fields,
                if (page.savesTokens) totals[m.id] ?: Stats() else null,
            )
        }
    }

    fun json(
        cfg: Config,
        store: Store?,
    ): JsonElement = JsonArray(build(cfg, store).map { it.toJson() })

    /** rows / est_before / est_after per plugin, every kind summed in (an `expand` row nets negative). */
    private fun statTotals(store: Store?): Map<String, Stats> {
        val by = HashMap<String, Stats>()
        store ?: return by
        runCatching { store.measurementTotals() }.getOrNull()?.forEach { Overview.fold(by, it) }
        return by
    }

    /** The settings each plugin's page shows (model/mod.rs `config_fields`). */
    fun configFields(
        id: String,
        cfg: Config,
    ): List<Pair<String, String>> {
        val p = cfg.plugins
        return when (id) {
            "cmd" -> {
                listOf(kv("rewrite", p.cmd.rewrite), kv("trailer_min_lines", p.cmd.trailerMinLines))
            }

            "read" -> {
                listOf(kv("default_mode", p.read.defaultMode), kv("max_chars", p.read.maxChars))
            }

            "archive" -> {
                listOf(kv("keep_turns", p.archive.keepTurns), kv("min_tokens", p.archive.minTokens))
            }

            "inject" -> {
                listOf(kv("budget_tokens", p.inject.budgetTokens))
            }

            "guard" -> {
                listOf(kv("window_turns", p.guard.windowTurns))
            }

            "memory" -> {
                listOf(
                    kv("recall_titles", p.memory.recallTitles),
                    kv("recall_tokens", p.memory.recallTokens),
                    kv("prompt_recall", p.memory.promptRecall),
                    kv("sync_tokens", p.memory.syncTokens),
                )
            }

            "graph" -> {
                listOf(kv("max_tokens", p.graph.maxTokens))
            }

            "docs" -> {
                listOf(kv("query_limit", p.docs.queryLimit), kv("snippet_chars", p.docs.snippetChars), kv("max_tokens", p.docs.maxTokens))
            }

            "toon" -> {
                listOf(kv("min_rows", p.toon.minRows))
            }

            "proxy" -> {
                listOf(kv("enabled", cfg.proxy.enabled), kv("mode", cfg.proxy.mode), kv("port", cfg.proxy.port))
            }

            "measure" -> {
                listOf(kv("since", cfg.stats.since))
            }

            else -> {
                emptyList()
            }
        }
    }

    private fun kv(
        k: String,
        v: Any,
    ) = k to v.toString()
}
