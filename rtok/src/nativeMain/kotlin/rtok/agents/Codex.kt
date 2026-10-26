// Port of src/agents/codex/mod.rs (MCP and hooks): the Codex CLI and app share `~/.codex`. MCP is the
// `[mcp_servers.rtok]` table in `config.toml`, edited through [TomlDoc] so comments and other servers survive, with
// `default_tools_approval_mode = "writes"` (T528); hooks are the `codex` rows of [HookEvents] in the sibling
// `hooks.json`, Claude's layout spelled `rtok hook <event> --host codex` (T645); `--proxy` is [CodexProxy]; the plugin
// is [CodexPlugin], the only hook path once enabled (D21).
package rtok.agents

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import rtok.agent.sdk.Apply
import rtok.agent.sdk.HostJson
import rtok.agent.sdk.NO_CHANGES
import rtok.agent.sdk.judgeOwned
import rtok.agent.sdk.write
import rtok.config.Config
import rtok.sys.exists
import rtok.sys.readToString
import tomlaot.Shape
import tomlaot.TomlDoc
import tomlaot.TomlTable
import tomlaot.TomlValue

class Codex(
    val plugin: CodexPlugin = CodexPlugin(),
) : Agent {
    override val id = HOST

    override fun files(
        cfg: Config,
        kind: Kind,
    ) = listOf(cfg.setup.codex.configPath, hooksPath(cfg))

    override fun installed(
        cfg: Config,
        kind: Kind,
    ): List<String> {
        // The enabled plugin serves the hooks itself (D21); MCP is independent of it (T275/D33).
        val plugin = CodexPlugin.installed(cfg)
        return listOfNotNull(
            "mcp".takeIf { McpEntries.hasTomlEntry(cfg.setup.codex.configPath, SERVERS, NAME) },
            "proxy".takeIf { "[model_providers.rtok]" in read(cfg.setup.codex.configPath) },
            "hooks".takeIf { plugin || " hook PreCompact" in read(hooksPath(cfg)) },
            "plugin".takeIf { plugin },
        )
    }

    override fun apply(
        cfg: Config,
        kind: Kind,
        mode: Mode,
    ): List<String> {
        val remove = mode == Mode.Remove
        // Plugin first: once enabled it is the only hook path (D21), judged by Codex's own record, so a dry run or a
        // declined offer still gets the file hooks.
        val update = mode == Mode.Update && CodexPlugin.installed(cfg) && CodexPlugin.marketplaceState(cfg) == MarketplaceState.Current
        val head = if (update) plugin.update(cfg) else plugin.offer(cfg, remove)
        // On the way out the provider block goes whether or not `--proxy` asked for it.
        val proxy = if (remove || cfg.setup.proxy) CodexProxy.run(cfg, remove) else null
        return listOfNotNull(head, runMcp(cfg, remove), runHooks(cfg, remove || CodexPlugin.installed(cfg)), proxy)
    }

    companion object {
        const val HOST = "codex"
        private const val NAME = "rtok"
        private const val SERVERS = "mcp_servers"
        private const val TABLE = "$SERVERS.$NAME"

        /**
         * Server-wide approval (T528): without it Codex asks before every rtok call and fails where nobody can answer.
         * `writes` skips the ask only for read-only tools; codex-cli refuses any value outside its set, so it is a constant.
         */
        const val APPROVAL_KEY = "default_tools_approval_mode"
        const val APPROVAL = "writes"

        fun hooksPath(cfg: Config) = withFileName(cfg.setup.codex.configPath, "hooks.json")

        private fun read(path: String) = if (exists(path)) runCatching { readToString(path) }.getOrDefault("") else ""

        /** Both spellings are ours: the compaction hooks were written without `--host` before T645 and upgrade in place. */
        val FORM =
            HookForm(
                { bin, event -> ClaudeHooks.commandFor(HOST, bin, event) },
                { cmd, event -> ClaudeHooks.isOursFor(HOST, cmd, event) || ClaudeHooks.isOurs(cmd, event) },
            )

        /** Register (or strip) the `codex` rows in `hooks.json`: the Bash rewrite, tool context and the compaction pair. */
        fun runHooks(
            cfg: Config,
            remove: Boolean,
        ): String {
            val path = hooksPath(cfg)
            val spec = HookSpec(HookEvents.entries(HOST), "timeout", cfg.setup.hookTimeoutS.toLong())
            val ctx = HookEdit(FORM, applyOf(cfg), path, spec)
            return HostJson.edit(ctx.apply, path) { root -> ClaudeHooks.edit(root, remove, ctx) }
        }

        /** Apply, dry-run, or remove `[mcp_servers.rtok]`. */
        fun runMcp(
            cfg: Config,
            remove: Boolean,
        ): String {
            val path = cfg.setup.codex.configPath
            val doc = HostToml.load(path)
            val report = if (remove) stripMcp(applyOf(cfg), path, doc) else insertMcp(doc, path)
            write(applyOf(cfg), path, doc.toString(), report)
            return report
        }

        private fun insertMcp(
            doc: TomlDoc,
            path: String,
        ): String {
            check(doc.shape(SERVERS) in setOf(Shape.Absent, Shape.Table)) { "$SERVERS is not a table in $path" }
            val cmd = rtokMcpCommand()
            doc.table(TABLE)?.takeIf(::isOurs)?.let { return refresh(it, cmd) }
            doc.removeTable(TABLE)
            val args = StdioMcp.args(HOST)
            val t = doc.ensureTable(TABLE)
            t["command"] = TomlValue.Str(cmd)
            t["args"] = HostToml.strings(args)
            t[APPROVAL_KEY] = TomlValue.Str(APPROVAL)
            return "+ [$TABLE]\ncommand = \"$cmd\"\nargs = [\"${args.joinToString("\", \"")}\"]\n$APPROVAL_KEY = \"$APPROVAL\""
        }

        /** T932: only a bare `rtok` moves to the resolved binary; an entry from before T528 gains the approval key. */
        private fun refresh(
            ours: TomlTable,
            cmd: String,
        ): String {
            val changes = mutableListOf<String>()
            if (cmd != "rtok" && ours.str("command") == "rtok") {
                ours["command"] = TomlValue.Str(cmd)
                changes += "~ [$TABLE] command = \"$cmd\""
            }
            if (!ours.containsKey(APPROVAL_KEY)) {
                ours[APPROVAL_KEY] = TomlValue.Str(APPROVAL)
                changes += "+ [$TABLE] $APPROVAL_KEY = \"$APPROVAL\""
            }
            return if (changes.isEmpty()) NO_CHANGES else changes.joinToString("\n")
        }

        /** Take the slot back only as far as rtok wrote it (T246.5); our own approval value does not count as an edit. */
        private fun stripMcp(
            apply: Apply,
            path: String,
            doc: TomlDoc,
        ): String {
            val have = doc.table(TABLE)?.let(HostToml::toJson) ?: return NO_CHANGES
            val judged = if (have[APPROVAL_KEY] == JsonPrimitive(APPROVAL)) JsonObject(have - APPROVAL_KEY) else have
            val ours = JsonObject(mapOf("command" to JsonPrimitive("rtok"), "args" to JsonArray(StdioMcp.args(HOST).map(::JsonPrimitive))))
            return judgeOwned(apply, "$TABLE in $path", judged, ours, ::isRtokBin) ?: "- [$TABLE]".also { doc.removeTable(TABLE) }
        }

        private fun isOurs(t: TomlTable): Boolean {
            val json = HostToml.toJson(t)
            return str(json, "command")?.let(::isRtokBin) == true && json["args"] == JsonArray(StdioMcp.args(HOST).map(::JsonPrimitive))
        }
    }
}
