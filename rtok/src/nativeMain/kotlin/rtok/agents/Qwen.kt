// Port of src/agents/qwen/mod.rs: Qwen Code. `<dir>/settings.json` (`[setup.qwen] dir`, default `~/.qwen`) holds
// Claude-layout hooks (Claude's event names, `timeout` in seconds, `rtok hook <event>` with no `--host`) and the
// stdio `mcpServers.rtok`. `plugins/qwen` is the D21 hooks unit behind `--yes` (`qwen extensions link`).
package rtok.agents

import rtok.agent.sdk.HostJson
import rtok.config.Config
import rtok.sys.isDir
import rtok.sys.join
import rtok.sys.listDir
import rtok.sys.readToString

class Qwen(
    private val plugins: PluginSource = ShippedPlugins,
    private val cli: (Config, List<String>) -> String? = ::qwenCli,
) : Agent {
    override val id = HOST

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
            "hooks".takeIf { " hook PreToolUse" in text },
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
            registerMcp = { StdioMcp.register(it, settingsPath(it), HOST) },
            unregisterMcp = { StdioMcp.unregister(it, settingsPath(it), HOST) },
        ).apply(cfg, mode)

    /** Apply, dry-run, or remove rtok's `hooks.<Event>[]` entries; `timeout` is seconds (Qwen reads >= 1000 as ms). */
    fun run(
        cfg: Config,
        remove: Boolean,
    ): String {
        val a = applyOf(cfg)
        val path = settingsPath(cfg)
        val ctx = HookEdit(ClaudeHooks.CLAUDE, a, path, HookSpec(HookEvents.entries(HOST), "timeout", cfg.setup.hookTimeoutS.toLong()))
        return HostJson.edit(a, path) { root -> ClaudeHooks.edit(root, remove, ctx) }
    }

    /** True while some `extensions/<any>/qwen-extension.json` names rtok (the link's folder name is not pinned). */
    fun pluginInstalled(cfg: Config): Boolean =
        extensionsDir(cfg).let { dir ->
            isDir(dir) && runCatching { listDir(dir) }.getOrDefault(emptyList()).any { PluginOffers.manifestNames(join(dir, it), MANIFEST, NAME) }
        }

    private fun plugin(
        cfg: Config,
        remove: Boolean,
    ): String = PluginOffers.offerPlugin(cfg, remove, pluginInstalled(cfg), OFFER, plugins) { cli(cfg, it) }

    companion object {
        const val HOST = "qwen"
        private const val NAME = "rtok"
        private const val MANIFEST = "qwen-extension.json"
        private val OFFER = PluginOffer(HOST, NAME, "plugins/qwen", listOf("extensions", "link"), listOf("extensions", "uninstall"))

        fun settingsPath(cfg: Config) = join(cfg.setup.qwen.dir, "settings.json")

        fun extensionsDir(cfg: Config) = join(cfg.setup.qwen.dir, "extensions")

        /** One `qwen …` call; `QWEN_HOME` only when `dir` is not the default, so the CLI links where we look. */
        fun qwenCli(
            cfg: Config,
            args: List<String>,
        ): String? {
            val dir = cfg.setup.qwen.dir
            val env = ("QWEN_HOME" to dir).takeIf { dir != join(McpPaths.home(), ".qwen") }
            return HostCli.runCli(cfg, HOST, args, env)
        }
    }
}
