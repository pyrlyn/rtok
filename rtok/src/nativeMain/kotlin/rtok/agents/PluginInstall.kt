// Port of src/agents/plugin_install.rs: where each host keeps an installed rtok plugin on disk (T279 step 2, T279.1).
// Feeds [PluginVersion.installed] — no network, no host CLI.
package rtok.agents

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import rtok.config.Config
import rtok.sys.isDir
import rtok.sys.isFile
import rtok.sys.join
import rtok.sys.listDir
import rtok.sys.parent
import rtok.sys.readToString

/** Inputs to [PluginVersion.installed] besides the receipt row. */
data class InstallProbe(
    val versionFile: String,
    val hostRecordVersion: String?,
    val fallbackSource: Source,
)

object PluginInstall {
    private val MANIFESTS =
        listOf(
            "plugin.json",
            ".cursor-plugin/plugin.json",
            ".codex-plugin/plugin.json",
            ".codebuddy-plugin/plugin.json",
            "gemini-extension.json",
            "kimi.plugin.json",
            "devin.plugin.json",
            "package.json",
        )

    /** Each host's own layout, read when the receipt has no row for it. */
    private val LAYOUT: Map<String, (Config, Kind) -> InstallProbe?> =
        mapOf(
            "claude" to { cfg, kind -> if (kind == Kind.Cli) claudeProbe(cfg) else null },
            "codex" to { cfg, _ -> cacheProbe(CodexPlugin.configDir(cfg), Source.Github) },
            "codebuddy" to { cfg, kind -> cacheProbe(Codebuddy.dir(cfg, kind), Source.Local) },
            "cursor" to { cfg, _ -> found(Cursor.PLUGIN.path(cfg), Source.Local) },
            "opencode" to { cfg, kind -> found(OpenCode.PLUGIN.path(OpenCode.forKind(cfg, kind)), Source.Local) },
            "kilo" to { cfg, _ -> found(Kilo.PLUGIN.path(cfg), Source.Local) },
            "mimo" to { cfg, _ -> found(Mimo.PLUGIN.path(cfg), Source.Local) },
            "pi" to { cfg, _ -> found(Pi.PLUGIN.path(cfg), Source.Local) },
            "omp" to { cfg, _ -> found(Omp.PLUGIN.path(cfg), Source.Local) },
            "zcode" to { cfg, _ -> found(Zcode.PLUGIN.path(cfg), Source.Local) },
            "gemini" to { cfg, _ -> tree(join(cfg.setup.gemini.dir, "extensions/rtok"), "gemini-extension.json", Source.Marketplace) },
            "copilot" to { cfg, _ -> manifestParent(join(cfg.setup.copilot.dir, "installed-plugins"), "plugin.json")?.let { found(it, Source.Marketplace) } },
            "grok" to { cfg, _ -> found(Grok.pluginMarker(cfg), Source.Marketplace) },
            "vscode" to { cfg, _ -> listOf(false, true).map(Vscode::plugin).firstOrNull { it.ours(cfg) }?.let { found(it.path(cfg), Source.Marketplace) } },
            "kimi" to { cfg, _ -> parent(Kimi.pluginMarker(cfg))?.let { found(it, Source.Marketplace) } },
            "antigravity" to { cfg, kind ->
                found(if (kind == Kind.Cli) Antigravity.cliPluginDest(cfg) else Antigravity.PLUGIN.path(cfg), Source.Marketplace)
            },
        )

    /** The installed copy for [host] when its plugin module is present; the receipt row wins over the host's layout. */
    fun probe(
        host: String,
        cfg: Config,
        kind: Kind,
        receipt: Receipt,
    ): InstallProbe? = receipt.get(host)?.let { InstallProbe(join(it.path, PluginVersion.FILE), null, it.source) } ?: LAYOUT[host]?.invoke(cfg, kind)

    private fun found(
        dir: String,
        fallback: Source,
    ) = InstallProbe(join(dir, PluginVersion.FILE), manifestVersion(dir), fallback)

    private fun tree(
        root: String,
        manifest: String,
        fallback: Source,
    ): InstallProbe? = (if (PluginOffers.manifestNames(root, manifest, NAME)) root else manifestParent(root, manifest))?.let { found(it, fallback) }

    /** A child of [root] (or a grandchild) whose [manifest] names rtok. */
    private fun manifestParent(
        root: String,
        manifest: String,
    ): String? =
        dirs(root).firstNotNullOfOrNull { outer ->
            outer.takeIf { PluginOffers.manifestNames(it, manifest, NAME) }
                ?: listChildren(outer).firstOrNull { PluginOffers.manifestNames(it, manifest, NAME) }
        }

    private fun listChildren(dir: String) = runCatching { listDir(dir) }.getOrDefault(emptyList()).map { join(dir, it) }

    private fun dirs(dir: String) = listChildren(dir).filter(::isDir)

    /** The first manifest beside the plugin that carries a string `version`. */
    fun manifestVersion(dir: String): String? =
        MANIFESTS.firstNotNullOfOrNull { m ->
            val path = join(dir, m)
            val text = if (isFile(path)) runCatching { readToString(path) }.getOrDefault("") else ""
            val root = text.takeIf { it.isNotEmpty() }?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() } as? JsonObject
            (root?.get("version") as? JsonPrimitive)?.takeIf { it.isString }?.content
        }

    private fun claudeProbe(cfg: Config): InstallProbe? = ClaudeUpdate.claudeInstall(cfg)?.let { (version, install) -> InstallProbe(join(install, PluginVersion.FILE), version, Source.Github) }

    /** The `plugins/cache/rtok/rtok/<version>` copy a host unpacks into [home]; the newest version directory wins (T628). */
    fun cacheProbe(
        home: String,
        fallback: Source,
    ): InstallProbe {
        val base = join(home, ClaudeUpdate.PLUGIN_CACHE)
        return found(dirs(base).maxWithOrNull(VERSION_DIR_ORDER) ?: base, fallback)
    }

    private const val NAME = "rtok"
}
