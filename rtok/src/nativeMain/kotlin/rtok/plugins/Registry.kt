// Port of src/plugins/mod.rs — the plugin registry. Order in `all()` is dispatch order.
package rtok.plugins

import rtok.config.Config
import rtok.plugin.sdk.DashboardPage
import rtok.plugin.sdk.Manifest
import rtok.plugin.sdk.Plugin
import rtok.pluginsTable

/** Every compiled-in plugin, in dispatch order. Grows as Phase 5 ports each plugin. */
fun all(): List<Plugin> = listOf(MeasurePlugin, rtok.plugins.cmd.CmdPlugin, rtok.plugins.read.ReadPlugin, rtok.plugins.jsontree.JsonTreePlugin, rtok.plugins.archive.ArchivePlugin, ProxyPlugin, rtok.plugins.inject.InjectPlugin, rtok.plugins.guard.GuardPlugin, rtok.plugins.memory.MemoryPlugin, rtok.plugins.graph.GraphPlugin, rtok.plugins.toon.ToonPlugin, rtok.plugins.compress.CompressPlugin, rtok.plugins.docs.DocsPlugin)

/** Compiled-in plugins plus their enabled state from config. */
class Registry private constructor(
    private val plugins: List<Pair<Plugin, Boolean>>,
) {
    /** Enabled plugins in dispatch order. */
    fun enabled(): List<Plugin> = plugins.filter { it.second }.map { it.first }

    /** `(manifest, enabled)` for every compiled-in plugin. */
    fun manifests(): List<Pair<Manifest, Boolean>> = plugins.map { (p, on) -> p.manifest() to on }

    /** `(manifest, enabled, page)` for every compiled-in plugin. */
    fun pages(): List<Triple<Manifest, Boolean, DashboardPage>> = plugins.map { (p, on) -> Triple(p.manifest(), on, p.dashboardPage()) }

    /** The `rtok plugins` table. */
    fun table(): String = pluginsTable(manifests().map { (m, on) -> Triple(m.id, on, m.surfaces.map { it.asStr() }) })

    companion object {
        /** Registry over every compiled-in plugin. */
        fun new(config: Config) = fromPlugins(all(), config)

        /** Registry over a caller-supplied plugin list (embedding rtok with external plugins). */
        fun fromPlugins(
            plugins: List<Plugin>,
            config: Config,
        ) = Registry(
            wasmPlugins(
                plugins.map { p ->
                    val m = p.manifest()
                    p to config.pluginEnabled(m.id, m.defaultOn)
                },
                config,
            ),
        )
    }
}
