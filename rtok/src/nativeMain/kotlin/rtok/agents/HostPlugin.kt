// Port of src/agents/plugin.rs `HostPlugin`: one host's plugin as four values (source, host name, label, destination)
// plus the SDK's offer/link/unlink cycle; a new host plugin is a declaration, not a cycle of its own.
package rtok.agents

import rtok.agent.sdk.CopyFix
import rtok.agent.sdk.KETCH_INSTALL
import rtok.agent.sdk.LinkHealth
import rtok.agent.sdk.PluginLink
import rtok.agent.sdk.keepBytes
import rtok.config.Config

data class HostPlugin(
    /** Repo-relative source, named in every report (`plugins/cursor`). */
    val srcRel: String,
    /** The host's name as its users spell it. */
    val host: String,
    /** Home-relative label when the real destination reads worse. */
    val label: String?,
    /** Where the plugin lands under a given config. */
    val dest: (Config) -> String,
    /** Installed without asking once the host is detected (T164): the local link is the only path there is. */
    val defaultInstall: Boolean,
) {
    fun path(cfg: Config) = dest(cfg)

    /** Anything at all sits at the destination, ours or a host's own. */
    fun linked(
        cfg: Config,
        plugins: PluginSource = ShippedPlugins,
    ) = link(cfg, plugins).linked()

    /** The destination is rtok's, exactly what `remove` will take back (T75). */
    fun ours(
        cfg: Config,
        plugins: PluginSource = ShippedPlugins,
    ) = link(cfg, plugins).ours()

    /** Offer, link, or unlink; a write error never aborts the rest of `agents install` (T139): it becomes the report. */
    fun offer(
        cfg: Config,
        remove: Boolean,
        plugins: PluginSource = ShippedPlugins,
        fix: CopyFix = keepBytes,
    ): String {
        val apply = applyOf(cfg).let { if (defaultInstall && !remove) it.copy(yes = true) else it }
        return runCatching { link(cfg, plugins).run(apply, remove, fix) }.getOrElse { e ->
            if (remove) "$host plugin failed: ${e.message}" else "offer $srcRel → ${label ?: path(cfg)} ($host failed: ${e.message}) $KETCH_INSTALL"
        }
    }

    /** What `doctor` says about the destination (T925): a dangling link or one an upgrade breaks. */
    fun problem(
        cfg: Config,
        plugins: PluginSource = ShippedPlugins,
    ): String? =
        when (val h = link(cfg, plugins).health()) {
            is LinkHealth.Dangling -> "$host plugin link ${path(cfg)} points at ${h.target}, which is gone"
            is LinkHealth.Stale -> "$host plugin ${path(cfg)} ${h.why}"
            else -> null
        }

    private fun link(
        cfg: Config,
        plugins: PluginSource,
    ) = PluginLink(srcRel, plugins.of(srcRel), dest(cfg), label, host)
}
