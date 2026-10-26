// Port of src/agents/hook_events.rs: the one table of hook events rtok registers per host — which host event runs
// which `rtok hook <event>`, under which matcher. Row order is the order an installer writes its entries in.
package rtok.agents

/** One host event and the `rtok hook <rtokEvent>` it runs. */
data class HookEvent(
    val host: String,
    val hostEvent: String,
    /** What follows `rtok hook`; Claude-shaped hosts pass their own event name. */
    val rtokEvent: String,
    /** The host's tool matcher; empty omits the field. */
    val matcher: String,
    /** The host's own installer writes this row; `false` marks a plugin-manifest-only row. */
    val installer: Boolean,
)

object HookEvents {
    private fun m(
        host: String,
        event: String,
        rtok: String,
        matcher: String,
        installer: Boolean = true,
    ) = HookEvent(host, event, rtok, matcher, installer)

    private fun r(
        host: String,
        event: String,
        rtok: String = event,
        installer: Boolean = true,
    ) = HookEvent(host, event, rtok, "", installer)

    private fun claudeShaped(
        host: String,
        installer: Boolean,
        subagent: Boolean,
    ) = listOf(
        m(host, "PreToolUse", "PreToolUse", "Bash", installer),
        m(host, "PreToolUse", "PreToolUse", "Read", installer),
        m(host, "PreToolUse", "PreToolUse", "Skill", installer),
        m(host, "PostToolUse", "PostToolUse", "*", installer),
    ) + listOf("UserPromptSubmit", "SessionStart", "PreCompact", "PostCompact", "SessionEnd").map { r(host, it, installer = installer) } +
        if (subagent) listOf(r(host, "SubagentStart", installer = installer)) else emptyList()

    /**
     * Every row. Cursor's `subagentStart` and `beforeSubmitPrompt` stay out on purpose (no context field in their output).
     * CodeBuddy takes Claude's rows minus SubagentStart (T560.2); Qwen documents Claude's names (research.md §32.2).
     */
    val ALL: List<HookEvent> =
        claudeShaped("claude", installer = false, subagent = true) +
            claudeShaped("codebuddy", installer = true, subagent = false) +
            listOf("PreToolUse", "PostToolUse", "TaskStart", "UserPromptSubmit", "SessionEnd").map { r("cline", it) } +
            listOf(
                m("commandcode", "PreToolUse", "PreToolUse", "SHELL|READ|WRITE|EDIT"),
                m("commandcode", "PostToolUse", "PostToolUse", ".*"),
                r("commandcode", "SessionStart"),
                r("commandcode", "Stop", "SessionEnd"),
                // Codex has no Read or Skill tool; edits are apply_patch (T262.3).
                m("codex", "PreToolUse", "PreToolUse", "Bash|apply_patch"),
                m("codex", "PostToolUse", "PostToolUse", "*"),
                r("codex", "PreCompact"),
                r("codex", "PostCompact"),
                r("codex", "SubagentStart"),
                r("copilot", "preToolUse", "PreToolUse"),
                r("copilot", "postToolUse", "PostToolUse"),
                r("copilot", "userPromptSubmitted", "UserPromptSubmit"),
                r("copilot", "sessionStart", "SessionStart"),
                r("copilot", "sessionEnd", "SessionEnd"),
                r("copilot", "preCompact", "PreCompact"),
                r("copilot", "subagentStart", "SubagentStart"),
                r("cursor", "beforeShellExecution", "PreToolUse"),
                r("cursor", "afterShellExecution", "PostToolUse"),
                r("cursor", "sessionStart", "SessionStart", installer = false),
                r("cursor", "sessionEnd", "SessionEnd"),
                r("cursor", "preCompact", "PreCompact"),
                r("cursor", "afterMCPExecution", "AfterMCPExecution", installer = false),
                r("cursor", "postToolUse", "PostToolUse", installer = false),
                m("devin", "PreToolUse", "PreToolUse", "^exec$"),
                m("devin", "PreToolUse", "PreToolUse", "^read$"),
            ) +
            listOf("PostToolUse", "UserPromptSubmit", "SessionStart", "PostCompaction", "SessionEnd").map { r("devin", it) } +
            listOf(
                r("gemini", "BeforeTool", "PreToolUse"),
                r("gemini", "AfterTool", "PostToolUse"),
                r("gemini", "BeforeAgent", "UserPromptSubmit"),
                r("gemini", "SessionStart"),
                r("gemini", "SessionEnd"),
                r("gemini", "PreCompress", "PreCompact"),
            ) +
            claudeShaped("qwen", installer = true, subagent = true) +
            listOf("PreToolUse", "PostToolUse", "UserPromptSubmit", "SessionStart", "PreCompact", "PostCompact", "SessionEnd")
                .map { r("grok", it, installer = false) } +
            claudeShaped("kimi", installer = true, subagent = false) +
            listOf(
                m("zcode", "PreToolUse", "PreToolUse", "Bash"),
                m("zcode", "PreToolUse", "PreToolUse", "Read"),
                m("zcode", "PostToolUse", "PostToolUse", "*"),
                r("zcode", "UserPromptSubmit"),
                r("zcode", "SessionStart"),
            )

    /** Every row of [host], plugin-only ones included. */
    fun forHost(host: String) = ALL.filter { it.host == host }

    /** The rows [host]'s own installer writes to its config. */
    fun installerRows(host: String) = forHost(host).filter { it.installer }

    /** `(host event, rtok event)` for what [host]'s installer writes. */
    fun installed(host: String) = installerRows(host).map { it.hostEvent to it.rtokEvent }

    /** `(host event, matcher)` for every row of a Claude-shaped host. */
    fun entries(host: String) = forHost(host).map { it.hostEvent to it.matcher }
}
