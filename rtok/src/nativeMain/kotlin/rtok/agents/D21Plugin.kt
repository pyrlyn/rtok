// Port of src/agents/mod.rs `D21Plugin`, `d21_plugin_apply`, `no_extra`: the apply skeleton of a host whose local-path
// plugin is a D21 singleton over hooks only (Copilot, Gemini, Qwen). MCP is outside that unit (D33).
package rtok.agents

import rtok.config.Config

/** The per-host operations [apply] threads through. */
class D21Plugin(
    val offer: (Config, Boolean) -> String,
    val pluginInstalled: (Config) -> Boolean,
    val run: (Config, Boolean) -> String,
    val registerMcp: (Config) -> String,
    val unregisterMcp: (Config) -> String,
    /** A per-host tail line (Copilot's skill sync), or none. */
    val extra: (Config, Boolean) -> String? = { _, _ -> null },
) {
    /**
     * The plugin offer first; then the hook document, taken back while the plugin is installed or on removal; then
     * `mcpServers.rtok` on every install behind `[setup] mcp`, removed only on remove; then [extra].
     */
    fun apply(
        cfg: Config,
        mode: Mode,
    ): List<String> {
        val remove = mode == Mode.Remove
        val head = offer(cfg, remove)
        val hooks = run(cfg, remove || pluginInstalled(cfg))
        val mcp =
            when {
                remove -> unregisterMcp(cfg)
                cfg.setup.mcp -> registerMcp(cfg)
                else -> null
            }
        return listOfNotNull(head, hooks, mcp, extra(cfg, remove))
    }
}
