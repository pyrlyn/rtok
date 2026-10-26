// Port of src/agents/qwen/mod.rs: Qwen Code. `<dir>/settings.json` (`[setup.qwen] dir`, default `~/.qwen`) holds
// Claude-layout hooks (Claude's event names, `timeout` in seconds, `rtok hook <event>` with no `--host`) and the
// stdio `mcpServers.rtok`. `plugins/qwen` is the D21 hooks unit behind `--yes` (`qwen extensions link`).
package rtok.agents

import rtok.config.Config
import rtok.plugin.sdk.Surface

class Qwen(
    plugins: PluginSource = ShippedPlugins,
    cli: ((Config, List<String>) -> String?)? = null,
) : ExtensionHost(SPEC, plugins, cli) {
    override val pluginSurfaces = listOf(Surface.Hook)

    companion object {
        const val HOST = "qwen"

        /** `timeout` is seconds (Qwen reads >= 1000 as ms). */
        private val SPEC =
            ExtensionSpec(HOST, { it.setup.qwen.dir }, "qwen-extension.json", "QWEN_HOME", " hook PreToolUse") { cfg, path ->
                HookEdit(ClaudeHooks.CLAUDE, applyOf(cfg), path, HookSpec(HookEvents.entries(HOST), "timeout", cfg.setup.hookTimeoutS.toLong()))
            }

        fun settingsPath(cfg: Config) = SPEC.settingsPath(cfg)

        fun extensionsDir(cfg: Config) = SPEC.extensionsDir(cfg)
    }
}
