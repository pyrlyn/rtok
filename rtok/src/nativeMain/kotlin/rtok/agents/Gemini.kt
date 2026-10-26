// Port of src/agents/gemini/mod.rs: Gemini CLI. `<dir>/settings.json` (`[setup.gemini] dir`, default `~/.gemini`)
// holds `hooks.<GeminiEvent>[]` (Gemini's own event names from [HookEvents], no `matcher` since Gemini matches its own
// tool spellings, `timeout` in ms, `<bin> hook <ClaudeEvent> --host gemini`) and the stdio `mcpServers.rtok`.
// `plugins/gemini` is the D21 hooks unit behind `--yes` (`gemini extensions link`, `GEMINI_CLI_HOME` for a moved dir).
package rtok.agents

import rtok.config.Config

class Gemini(
    plugins: PluginSource = ShippedPlugins,
    cli: ((Config, List<String>) -> String?)? = null,
) : ExtensionHost(SPEC, plugins, cli) {
    companion object {
        const val HOST = "gemini"

        /** Gemini event -> the rtok (Claude) event its hook command names. */
        private val RTOK_EVENT: Map<String, String> = HookEvents.installed(HOST).toMap()

        /** Exactly `<rtok-bin> hook <event> --host gemini`; the tail match keeps an absolute (Windows) path ours. */
        val FORM =
            HookForm(
                { bin, g -> "$bin hook ${RTOK_EVENT.getValue(g)} --host $HOST" },
                { cmd, g -> RTOK_EVENT[g]?.let { e -> " hook $e --host $HOST".let { s -> cmd.endsWith(s) && isRtokBin(HookLine.unquoteBin(cmd.removeSuffix(s))) } } == true },
            )

        fun hookSpec(timeoutS: Long) = HookSpec(RTOK_EVENT.keys.map { it to "" }, "timeout", timeoutS * MS)

        private const val MS = 1000L

        private val SPEC =
            ExtensionSpec(HOST, { it.setup.gemini.dir }, "gemini-extension.json", "GEMINI_CLI_HOME", " hook PreToolUse --host gemini") { cfg, path ->
                HookEdit(FORM, applyOf(cfg), path, hookSpec(cfg.setup.hookTimeoutS.toLong()))
            }

        fun settingsPath(cfg: Config) = SPEC.settingsPath(cfg)

        fun extensionsDir(cfg: Config) = SPEC.extensionsDir(cfg)
    }
}
