// Port of src/agents/codex/mod.rs `plugin`, `plugin_installed`, `marketplace_state`, `plugin_update`, `cache_bytes`
// (T140, T242.4): `plugins/codex` through the real `codex plugin` commands from the GitHub marketplace `pyrlyn/rtok`,
// added by default once `codex` is on PATH. Codex records both in `config.toml`: `[marketplaces.rtok]` (`source_type =
// "git"`, the clone URL) and `[plugins."rtok@rtok"] enabled = true`. A `rtok` marketplace under another source is
// re-pointed (remove, then add), since `marketplace add` refuses a different source. A failing or missing `codex` keeps
// the offer open instead of failing the install.
package rtok.agents

import rtok.agent.sdk.KETCH_INSTALL
import rtok.agent.sdk.NO_CHANGES
import rtok.config.Config
import rtok.sys.readBytes
import rtok.sys.walk
import tomlaot.TomlTable
import tomlaot.TomlValue

class CodexPlugin(
    private val call: (Config, List<String>, Pair<String, String>?) -> String? = { c, a, e -> HostCli.runCli(c, Codex.HOST, a, e) },
    val onPath: (Config) -> Boolean = { Locate.cli(it, Codex.HOST) != null },
) {
    /** Offer, add, or remove the plugin; enabled from the GitHub marketplace (or already gone) is no change. */
    fun offer(
        cfg: Config,
        remove: Boolean,
    ): String {
        val installed = installed(cfg)
        val state = marketplaceState(cfg)
        val skip = if (remove) !installed else installed && state != MarketplaceState.Stale
        if (skip) return NO_CHANGES
        val steps = if (remove) listOf(REMOVE, MARKET_REMOVE) else installSteps(installed, state)
        val shown = shown(steps)
        return when {
            applyOf(cfg).dryRun && remove -> {
                "- plugin $PLUGIN_ID ($shown)"
            }

            applyOf(cfg).dryRun -> {
                "offer $PLUGIN_SRC → $shown $KETCH_INSTALL"
            }

            !remove && !onPath(cfg) -> {
                "offer $PLUGIN_SRC → $shown (codex failed: codex not found on PATH) $KETCH_INSTALL"
            }

            else -> {
                steps.firstNotNullOfOrNull { cli(cfg, it) }?.let { "offer $PLUGIN_SRC → $shown (codex failed: $it)" }
                    ?: if (remove) "- plugin $PLUGIN_ID" else "+ plugin $PLUGIN_SRC → $PLUGIN_ID"
            }
        }
    }

    /**
     * `agents update` over a plugin enabled from the GitHub marketplace (T242.4): `marketplace upgrade rtok` refreshes
     * the cache in place; a failed upgrade reinstalls the whole chain. An unchanged cache is no change.
     */
    fun update(cfg: Config): String =
        when {
            applyOf(cfg).dryRun -> "~ plugin $PLUGIN_ID (${shown(listOf(UPGRADE))})"
            !onPath(cfg) -> NO_CHANGES
            else -> upgrade(cfg)
        }

    private fun upgrade(cfg: Config): String {
        val before = cacheBytes(cfg)
        val err = cli(cfg, UPGRADE) ?: return if (cacheBytes(cfg) == before) NO_CHANGES else "~ plugin $PLUGIN_ID updated"
        val reinstall = listOf(REMOVE, MARKET_REMOVE, MARKET_ADD, ADD)
        return reinstall.firstNotNullOfOrNull { cli(cfg, it) }?.let { "offer $PLUGIN_SRC → ${shown(reinstall)} (codex failed: $it)" }
            ?: "~ plugin $PLUGIN_ID reinstalled (update failed: $err)"
    }

    /** One `codex …` call; `CODEX_HOME` only when `config_path` is not the default. Null on success. */
    private fun cli(
        cfg: Config,
        args: List<String>,
    ): String? = call(cfg, args, configDir(cfg).takeIf { it != join(McpPaths.home(), ".codex") }?.let { "CODEX_HOME" to it })

    private fun installSteps(
        installed: Boolean,
        state: MarketplaceState,
    ): List<List<String>> {
        val stale = state == MarketplaceState.Stale
        return listOfNotNull(
            REMOVE.takeIf { stale && installed },
            MARKET_REMOVE.takeIf { stale },
            MARKET_ADD.takeIf { state != MarketplaceState.Current },
            ADD,
        )
    }

    companion object {
        const val PLUGIN_SRC = "plugins/codex"
        const val PLUGIN_ID = "rtok@rtok"
        private const val MARKETPLACE_REPO = "pyrlyn/rtok"

        /** What `codex plugin marketplace add pyrlyn/rtok` records (codex-cli 0.155.1). */
        const val MARKETPLACE_GIT_SOURCE = "https://github.com/pyrlyn/rtok.git"

        private val ADD = listOf("plugin", "add", PLUGIN_ID)
        private val REMOVE = listOf("plugin", "remove", PLUGIN_ID)
        private val MARKET_ADD = listOf("plugin", "marketplace", "add", MARKETPLACE_REPO)
        private val MARKET_REMOVE = listOf("plugin", "marketplace", "remove", "rtok")
        private val UPGRADE = listOf("plugin", "marketplace", "upgrade", "rtok")

        private fun shown(steps: List<List<String>>) = steps.joinToString(" && ") { "codex ${it.joinToString(" ")}" }

        fun configDir(cfg: Config) = parentOf(cfg.setup.codex.configPath)

        private fun table(
            cfg: Config,
            path: String,
        ): TomlTable? = runCatching { HostToml.load(cfg.setup.codex.configPath).table(path) }.getOrNull()

        /** `[plugins."rtok@rtok"] enabled = true` in Codex's own config. */
        fun installed(cfg: Config) = table(cfg, "plugins.$PLUGIN_ID")?.get("enabled") == TomlValue.Bool(true)

        fun marketplaceState(cfg: Config): MarketplaceState {
            val t = table(cfg, "marketplaces.rtok") ?: return MarketplaceState.Absent
            val github = t.str("source_type") == "git" && t.str("source") == MARKETPLACE_GIT_SOURCE
            return if (github) MarketplaceState.Current else MarketplaceState.Stale
        }

        /** Every file of the installed plugin cache with its bytes, in path order: the only evidence an upgrade changed anything. */
        private fun cacheBytes(cfg: Config): List<Pair<String, List<Byte>>> =
            walk(join(configDir(cfg), "plugins/cache/rtok/rtok"), gitIgnore = false)
                .filter { it.isFile }
                .map { it.path to readBytes(it.path).toList() }
                .toList()
    }
}
