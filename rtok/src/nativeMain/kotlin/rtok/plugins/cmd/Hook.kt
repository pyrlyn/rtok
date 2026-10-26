// Port of src/plugins/cmd/hook.rs + mod.rs: the PreToolUse rewrite `<cmd>` → `rtok run -- '<cmd>'`.
package rtok.plugins.cmd

import kotlinx.serialization.json.*
import rtok.cmdStem
import rtok.config.Cmd
import rtok.plugin.sdk.*
import rtok.plugins.guard.stripCdHop
import rtok.runGuard

object CmdPlugin : Plugin {
    override fun manifest() = Manifest("cmd", listOf(Surface.Hook, Surface.Cli), true)

    override fun dashboardPage() = DashboardPage("Bash / cmd", "Archive and filter command output; expand the original by id.", true)

    override fun preTool(
        ev: PreToolUse,
        cx: Ctx,
    ) = CmdHook.preTool(ev, cx)
}

object CmdHook {
    private val STATE_BUILTINS =
        setOf(
            "cd",
            "pushd",
            "popd",
            "export",
            "source",
            ".",
            "unset",
            "alias",
            "unalias",
            "set",
            "shopt",
            "umask",
            "trap",
            "declare",
            "typeset",
            "readonly",
        )

    fun preTool(
        ev: PreToolUse,
        cx: Ctx,
    ): PreToolDecision? {
        val cfg = cx.pluginConfig("cmd", Cmd.serializer(), Cmd())
        if (ev.toolName != "Bash" || !cfg.rewrite) return null
        val full = ((ev.toolInput as? JsonObject)?.get("command") as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        if (skipWrap(full, cfg)) return null
        // A sub-agent's git must run unwrapped: the agent reads its own worktree state from it.
        if (cx.agentId() != null && runsGit(full)) return null
        var cmd = full
        while (true) {
            val rest = stripCdHop(cmd)?.second ?: break
            cmd = full.substring(full.length - rest.length)
        }
        if (cmd.length != full.length && skipWrap(cmd, cfg)) return null
        if (changesShellState(cmd)) return null
        val wrapped = full.substring(0, full.length - cmd.length) + runGuard() + "rtok run " + agentFlag(cx) + "-- " + wrapQuote(cmd)
        val input = JsonObject((ev.toolInput as JsonObject) + ("command" to JsonPrimitive(wrapped)))
        return PreToolDecision.Rewrite(input, "wrapped by rtok")
    }

    private fun skipWrap(
        cmd: String,
        cfg: Cmd,
    ) = skipWrapHost(false, cmd, cfg.neverWrap, cfg.interactiveStems)

    internal fun skipWrapHost(
        windows: Boolean,
        cmd: String,
        neverWrap: List<String>,
        interactiveStems: List<String>,
    ): Boolean {
        val tokens = cmd.split(' ', '\t', '\n', '\r').filter { it.isNotEmpty() }
        val base = cmdStem(tokens.firstOrNull() ?: "")
        val t = cmd.trimEnd()
        return neverWrap.any { it.equals(base, ignoreCase = true) } || "<<" in cmd || (windows && '\'' in cmd) ||
            "&" in tokens || "--interactive" in tokens ||
            ("-i" in tokens && interactiveISkip(base, tokens.drop(1), interactiveStems)) ||
            (t.endsWith('&') && !t.endsWith("&&"))
    }

    /** `-i` means interactive only for the listed stems; for docker/kubectl only with run/exec. */
    private fun interactiveISkip(
        stem: String,
        args: List<String>,
        stems: List<String>,
    ): Boolean {
        if (stems.none { it.equals(stem, ignoreCase = true) }) return false
        return when (stem.lowercase()) {
            "docker" -> args.any { it.equals("run", true) || it.equals("exec", true) }
            "kubectl" -> args.any { it.equals("exec", true) }
            else -> true
        }
    }

    private fun isValidAgentId(id: String) = id.isNotEmpty() && id.length <= 64 && id.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-' }

    private fun agentFlag(cx: Ctx) = cx.agentId()?.takeIf(::isValidAgentId)?.let { "--agent $it " } ?: ""

    private fun changesShellState(cmd: String) = Bounded.stages(cmd).any { it.firstOrNull() in STATE_BUILTINS || isBareAssignment(it) }

    private fun isBareAssignment(stage: List<String>) =
        stage.isNotEmpty() &&
            stage.all { w ->
                val name = w.substringBefore('=', missingDelimiterValue = "\u0000")
                name != "\u0000" && name.firstOrNull()?.let { it == '_' || it in 'a'..'z' || it in 'A'..'Z' } == true &&
                    name.all { it == '_' || it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }
            }

    /** Any word (letters, digits, `_`) spelling `git`, the way Rust splits on `!is_alphanumeric()`. */
    private fun runsGit(cmd: String): Boolean {
        val w = StringBuilder()
        for (c in "$cmd ") {
            if (c.isLetterOrDigit() || c == '_') {
                w.append(c)
            } else {
                if (w.toString().equals("git", true)) return true
                w.clear()
            }
        }
        return false
    }
}
