// Port of src/agents/opencode/mod.rs: OpenCode (CLI `[setup.opencode] config_path`, Desktop its own `opencode.json`) gets
// `env.OPENAI_BASE_URL` pointed at the proxy, the local `mcp.rtok` entry and `plugins/opencode/rtok.ts` linked beside
// the config (default install, T164). The forks Kilo Code and MiMo Code reuse [OpenCodeFamily].
package rtok.agents

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import rtok.agent.sdk.HostJson
import rtok.agent.sdk.NO_CHANGES
import rtok.config.Config
import rtok.mcp.config.Client
import rtok.mcp.config.EntryShape
import rtok.mcp.config.Format
import rtok.mcp.config.McpSpec

/** What OpenCode and its forks share: the local MCP entry and the `plugins/rtok.ts` link beside the config. */
object OpenCodeFamily {
    const val PLUGIN_REL = "plugins/opencode/rtok.ts"

    /** `<config dir>/plugins/rtok.ts`. */
    fun pluginDestBeside(configFile: String) = join(join(parentOf(configFile), "plugins"), "rtok.ts")

    fun plugin(
        host: String,
        configPath: (Config) -> String,
    ) = HostPlugin(PLUGIN_REL, host, null, { pluginDestBeside(configPath(it)) }, defaultInstall = true)

    /** `mcp.rtok = {type: local, command: [rtok, mcp, --host, <host>], enabled: true}`; a `.jsonc` file keeps comments. */
    fun mcpSpec(
        host: String,
        path: String,
    ) = McpSurfaces
        .jsonSpec(host, Client.Cli, path, "mcp", rtokMcpCommand(), EntryShape.LocalArgv)
        .let { if (path.endsWith(".jsonc")) it.copy(format = Format.Jsonc) else it }

    /** The MCP line and the plugin line every family member reports. */
    fun apply(
        cfg: Config,
        mode: Mode,
        spec: McpSpec,
        plugin: HostPlugin,
        plugins: PluginSource,
    ): List<String> = listOfNotNull(McpRun.sync(cfg, spec, mode), plugin.offer(cfg, mode == Mode.Remove, plugins))

    fun installed(
        cfg: Config,
        path: String,
        plugin: HostPlugin,
        plugins: PluginSource,
    ): List<String> = listOfNotNull("mcp".takeIf { mentionsRtok(path) }, "plugin".takeIf { plugin.ours(cfg, plugins) })
}

class OpenCode(
    private val plugins: PluginSource = ShippedPlugins,
) : Agent {
    override val id = HOST

    override fun files(
        cfg: Config,
        kind: Kind,
    ) = listOf(configPath(cfg, kind))

    override fun installed(
        cfg: Config,
        kind: Kind,
    ): List<String> {
        val c = forKind(cfg, kind)
        val path = c.setup.opencode.configPath
        val proxy = runCatching { rtok.sys.readToString(path) }.getOrDefault("").contains(ENV_KEY)
        return listOfNotNull("proxy".takeIf { proxy }) + OpenCodeFamily.installed(c, path, PLUGIN, plugins)
    }

    override fun apply(
        cfg: Config,
        kind: Kind,
        mode: Mode,
    ): List<String> {
        val c = forKind(cfg, kind)
        return listOf(runProxy(c, mode == Mode.Remove)) + OpenCodeFamily.apply(c, mode, mcpSpec(c), PLUGIN, plugins)
    }

    companion object {
        const val HOST = "opencode"
        private const val ENV = "env"
        private const val ENV_KEY = "OPENAI_BASE_URL"

        val PLUGIN = OpenCodeFamily.plugin("OpenCode") { it.setup.opencode.configPath }

        fun configPath(
            cfg: Config,
            kind: Kind,
        ) = if (kind == Kind.Desktop) McpPaths.opencodeDesktop() else cfg.setup.opencode.configPath

        /** [cfg] with `config_path` set to [kind]'s file, so every helper reads one place. */
        fun forKind(
            cfg: Config,
            kind: Kind,
        ) = cfg.copy(setup = cfg.setup.copy(opencode = cfg.setup.opencode.copy(configPath = configPath(cfg, kind))))

        fun mcpSpec(cfg: Config) = OpenCodeFamily.mcpSpec(HOST, cfg.setup.opencode.configPath)

        /** Set or drop `env.OPENAI_BASE_URL`; the report names how to revert. */
        fun runProxy(
            cfg: Config,
            remove: Boolean,
        ): String =
            HostJson.edit(applyOf(cfg), cfg.setup.opencode.configPath) { root ->
                if (remove) strip(root) else insert(root, ProxyUrl.openai(cfg))
            }

        private fun insert(
            root: JsonObject,
            url: String,
        ): Pair<JsonObject, String> {
            val env = root[ENV] as? JsonObject ?: JsonObject(emptyMap())
            val prev = env[ENV_KEY]
            if (prev == JsonPrimitive(url)) return root to NO_CHANGES
            val revert = (prev as? JsonPrimitive)?.takeIf { it.isString }?.let { "revert: set env.$ENV_KEY to ${it.content}" } ?: "revert: remove env.$ENV_KEY"
            return JsonObject(root + (ENV to JsonObject(env + (ENV_KEY to JsonPrimitive(url))))) to "env.$ENV_KEY: $url\n$revert"
        }

        private fun strip(root: JsonObject): Pair<JsonObject, String> {
            val env = root[ENV] as? JsonObject
            if (env == null || ENV_KEY !in env) return root to NO_CHANGES
            val left = JsonObject(env - ENV_KEY)
            return JsonObject(if (left.isEmpty()) root - ENV else root + (ENV to left)) to "- env.$ENV_KEY"
        }
    }
}

class Kilo(
    private val plugins: PluginSource = ShippedPlugins,
) : Agent {
    override val id = "kilo"

    override fun files(
        cfg: Config,
        kind: Kind,
    ) = listOf(cfg.setup.kilo.configPath)

    override fun installed(
        cfg: Config,
        kind: Kind,
    ) = OpenCodeFamily.installed(cfg, cfg.setup.kilo.configPath, PLUGIN, plugins)

    override fun apply(
        cfg: Config,
        kind: Kind,
        mode: Mode,
    ) = OpenCodeFamily.apply(cfg, mode, OpenCodeFamily.mcpSpec(id, cfg.setup.kilo.configPath), PLUGIN, plugins)

    companion object {
        val PLUGIN = OpenCodeFamily.plugin("Kilo Code") { it.setup.kilo.configPath }
    }
}

/** MiMo Code: `[setup.mimo] config_path`, or a lone `mimocode.jsonc` edited in place. */
class Mimo(
    private val plugins: PluginSource = ShippedPlugins,
) : Agent {
    override val id = "mimo"

    override fun files(
        cfg: Config,
        kind: Kind,
    ) = listOf(configPath(cfg))

    override fun installed(
        cfg: Config,
        kind: Kind,
    ) = OpenCodeFamily.installed(cfg, configPath(cfg), PLUGIN, plugins)

    override fun apply(
        cfg: Config,
        kind: Kind,
        mode: Mode,
    ) = OpenCodeFamily.apply(cfg, mode, OpenCodeFamily.mcpSpec(id, configPath(cfg)), PLUGIN, plugins)

    companion object {
        /** The plugin sits beside the configured file, whichever of the two Mimo reads. */
        val PLUGIN = OpenCodeFamily.plugin("MiMo Code") { it.setup.mimo.configPath }

        fun configPath(cfg: Config) = McpFiles.BY_HOST.getValue("mimo")(cfg, Kind.Cli).path
    }
}
