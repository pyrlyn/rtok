// The shape Qwen (agents/qwen/mod.rs) and Gemini (agents/gemini/mod.rs) share: one `<dir>/settings.json` holding
// `hooks.<Event>[]` and the stdio `mcpServers.rtok`, plus a D21 extension (`<bin> extensions link <plugins/host>`)
// behind `--yes` that takes the settings hooks back while linked. Only the hook spelling, the manifest name and the
// CLI's home variable differ, so each host passes those in.
package rtok.agents

import rtok.agent.sdk.HostJson
import rtok.config.Config
import rtok.sys.isDir
import rtok.sys.join
import rtok.sys.listDir
import rtok.sys.readToString

/** What one extension host differs in. */
class ExtensionSpec(
    val host: String,
    val dir: (Config) -> String,
    val manifest: String,
    val homeVar: String,
    /** A string present in `settings.json` while rtok's hooks are there. */
    val hookMarker: String,
    val hookEdit: (Config, String) -> HookEdit,
) {
    fun settingsPath(cfg: Config) = join(dir(cfg), "settings.json")

    fun extensionsDir(cfg: Config) = join(dir(cfg), "extensions")
}

abstract class ExtensionHost(
    private val spec: ExtensionSpec,
    private val plugins: PluginSource,
    private val cli: ((Config, List<String>) -> String?)?,
) : Agent {
    override val id = spec.host

    private val offer = PluginOffer(spec.host, NAME, "plugins/${spec.host}", listOf("extensions", "link"), listOf("extensions", "uninstall"))

    fun settingsPath(cfg: Config) = spec.settingsPath(cfg)

    fun extensionsDir(cfg: Config) = spec.extensionsDir(cfg)

    override fun files(
        cfg: Config,
        kind: Kind,
    ) = listOf(settingsPath(cfg))

    override fun installed(
        cfg: Config,
        kind: Kind,
    ): List<String> {
        val path = settingsPath(cfg)
        val text = runCatching { readToString(path) }.getOrDefault("")
        return listOfNotNull(
            "hooks".takeIf { spec.hookMarker in text },
            "mcp".takeIf { McpEntries.hasEntry(path, "mcpServers", NAME) },
            "plugin".takeIf { pluginInstalled(cfg) },
        )
    }

    override fun apply(
        cfg: Config,
        kind: Kind,
        mode: Mode,
    ): List<String> =
        D21Plugin(
            offer = ::plugin,
            pluginInstalled = ::pluginInstalled,
            run = ::run,
            registerMcp = { StdioMcp.register(it, settingsPath(it), spec.host) },
            unregisterMcp = { StdioMcp.unregister(it, settingsPath(it), spec.host) },
        ).apply(cfg, mode)

    /** Apply, dry-run, or remove rtok's `hooks.<Event>[]` entries; foreign entries survive. */
    fun run(
        cfg: Config,
        remove: Boolean,
    ): String {
        val path = settingsPath(cfg)
        val ctx = spec.hookEdit(cfg, path)
        return HostJson.edit(ctx.apply, path) { root -> ClaudeHooks.edit(root, remove, ctx) }
    }

    /** True while some `extensions/<any>/<manifest>` names rtok (the link's folder name is not pinned). */
    fun pluginInstalled(cfg: Config): Boolean =
        extensionsDir(cfg).let { dir ->
            isDir(dir) && runCatching { listDir(dir) }.getOrDefault(emptyList()).any { PluginOffers.manifestNames(join(dir, it), spec.manifest, NAME) }
        }

    private fun plugin(
        cfg: Config,
        remove: Boolean,
    ): String = PluginOffers.offerPlugin(cfg, remove, pluginInstalled(cfg), offer, plugins) { (cli ?: ::hostCli)(cfg, it) }

    /** One `<host> …` call; the home variable only when `dir` is not the default, so the CLI links where we look. */
    fun hostCli(
        cfg: Config,
        args: List<String>,
    ): String? {
        val dir = spec.dir(cfg)
        val env = (spec.homeVar to dir).takeIf { dir != join(McpPaths.home(), ".${spec.host}") }
        return HostCli.runCli(cfg, spec.host, args, env)
    }

    companion object {
        const val NAME = "rtok"
    }
}
