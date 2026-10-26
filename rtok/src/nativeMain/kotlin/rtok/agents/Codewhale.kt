// Port of src/agents/codewhale/mod.rs: CodeWhale (`codewhale`, `codew`) keeps hooks in `<dir>/config.toml` as
// `[hooks] enabled = true` plus one `[[hooks.hooks]]` table on `message_submit` (the one steering event with real stdin
// JSON, run as `hook UserPromptSubmit --host codewhale`), and MCP in `<dir>/mcp.json` as
// `mcpServers.rtok = {type: "stdio", command, args}`. The TOML goes through [TomlDoc], so comments and foreign hooks stay.
package rtok.agents

import rtok.agent.sdk.Apply
import rtok.agent.sdk.NO_CHANGES
import rtok.agent.sdk.write
import rtok.config.Config
import rtok.plugin.sdk.Surface
import rtok.sys.exists
import rtok.sys.join
import rtok.sys.readToString
import tomlaot.TomlDoc
import tomlaot.TomlTable
import tomlaot.TomlValue

class Codewhale : Agent {
    override val id = HOST
    override val pluginSurfaces = listOf(Surface.Mcp, Surface.Hook)

    override fun files(
        cfg: Config,
        kind: Kind,
    ) = listOf(configPath(cfg), mcpPath(cfg))

    override fun installed(
        cfg: Config,
        kind: Kind,
    ): List<String> {
        val path = configPath(cfg)
        val hooks = exists(path) && " hook $CLAUDE_EVENT --host $HOST" in runCatching { readToString(path) }.getOrDefault("")
        return listOfNotNull("hooks".takeIf { hooks }, "mcp".takeIf { mentionsRtok(mcpPath(cfg)) })
    }

    override fun apply(
        cfg: Config,
        kind: Kind,
        mode: Mode,
    ): List<String> {
        val remove = mode == Mode.Remove
        val mcp =
            when {
                remove -> StdioMcp.unregister(cfg, mcpPath(cfg), HOST)
                cfg.setup.mcp -> StdioMcp.register(cfg, mcpPath(cfg), HOST, typed = true)
                else -> null
            }
        return listOfNotNull(run(cfg, remove), mcp)
    }

    companion object {
        const val HOST = "codewhale"
        private const val NAME = "rtok"
        const val EVENT = "message_submit"
        private const val CLAUDE_EVENT = "UserPromptSubmit"
        private const val AOT = "hooks.hooks"
        private const val OWN_KEYS = 4

        fun configPath(cfg: Config) = join(cfg.setup.codewhale.dir, "config.toml")

        /** `mcp.json` beside `config.toml`, both under `$CODEWHALE_HOME`. */
        fun mcpPath(cfg: Config) = join(cfg.setup.codewhale.dir, "mcp.json")

        fun command(bin: String) = "$bin hook $CLAUDE_EVENT --host $HOST"

        /** Exactly `<rtok-bin> hook UserPromptSubmit --host codewhale`; only the tail is matched, so a full path counts. */
        fun isOurs(cmd: String): Boolean {
            val suffix = " hook $CLAUDE_EVENT --host $HOST"
            return cmd.endsWith(suffix) && isRtokBin(HookLine.unquoteBin(cmd.removeSuffix(suffix)))
        }

        private fun tableIsOurs(t: TomlTable) = t.str("event") == EVENT && t.str("command")?.let(::isOurs) == true

        /** Apply, dry-run, or remove the `[[hooks.hooks]]` entry. */
        fun run(
            cfg: Config,
            remove: Boolean,
        ): String {
            val path = configPath(cfg)
            val doc = HostToml.load(path)
            val apply = applyOf(cfg)
            val timeout = cfg.setup.hookTimeoutS.toLong()
            val report = if (remove) strip(apply, path, doc, timeout) else insert(doc, timeout)
            write(apply, path, doc.toString(), report)
            return report
        }

        /**
         * `[hooks] enabled = true` plus one `[[hooks.hooks]]` table. `enabled` is set only when absent, so a user's
         * `enabled = false` stays theirs.
         */
        fun insert(
            doc: TomlDoc,
            timeout: Long,
        ): String {
            if (doc.arrayOfTables(AOT).any(::tableIsOurs)) return NO_CHANGES
            val hooks = doc.ensureTable("hooks")
            if (!hooks.containsKey("enabled")) hooks["enabled"] = TomlValue.Bool(true)
            val cmd = command(HookLine.rtokHookBin())
            doc.push(
                AOT,
                listOf(
                    "name" to TomlValue.Str(NAME),
                    "event" to TomlValue.Str(EVENT),
                    "command" to TomlValue.Str(cmd),
                    "timeout_secs" to TomlValue.Int(timeout),
                ),
            )
            return "+ [[hooks.hooks]] $EVENT $cmd\n1 addition"
        }

        /** Remove rtok's table (T246.6): one as [insert] writes it goes; one the user changed as `keep_edited` decides. */
        fun strip(
            apply: Apply,
            path: String,
            doc: TomlDoc,
            timeout: Long,
        ): String {
            var removed = 0
            val kept = mutableListOf<String>()
            doc.retain(AOT) { t ->
                if (!tableIsOurs(t)) return@retain true
                val unchanged = t.size == OWN_KEYS && t.str("name") == NAME && t.int("timeout_secs") == timeout
                val take = StripReport.takesHook(apply, unchanged, { "[[hooks.hooks]] $EVENT in $path" }, kept)
                if (take) removed++
                !take
            }
            return StripReport.withKept(kept, StripReport.removedReport(removed))
        }
    }
}
