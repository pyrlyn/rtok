// The agent host registry (agents/mod.rs `host`, `Kind`, `Variant`, `Support`, each host folder's `VARIANTS` and
// `support`). Descriptors only: one data row per host, so adding a host is one entry. The install side (`files`,
// `installed`, `apply`) lands with `rtok agents`; [HostHookSupport] answers link.rs `hookless` from this table.
package rtok.agents

/** How the app runs: a terminal binary or a desktop application. */
enum class Kind(
    /** The block header word: `CLI: Codex`, `Desktop: Claude Desktop`. */
    val label: String,
    /** The flag spelling (`--cli`, `--desktop`) and the word `rtok doctor` prints. */
    val word: String,
) {
    Cli("CLI", "cli"),
    Desktop("Desktop", "desktop"),
}

/**
 * One app of a host. [bins] are looked up on PATH and asked `--version`; [apps] are where a desktop build installs
 * (`~/…`, `$VAR/…` or absolute). Entries for other platforms simply never exist.
 */
data class Variant(
    val kind: Kind,
    val name: String,
    val bins: List<String>,
    val apps: List<String>,
)

/** Whether `setup` can write a module into a host variant. */
sealed class Support {
    /** Written by a plain `setup`. */
    data object Yes : Support()

    /** Written only when this flag is given (`--proxy`, `--yes`). */
    data class Flag(
        val flag: String,
    ) : Support()

    /** Never written by `setup`: it prints how to install the module behind this flag; the host's installer owns it. */
    data class Offer(
        val flag: String,
    ) : Support()

    /** Cannot be written today; the reason is the README's, in one line. */
    data class No(
        val reason: String,
    ) : Support()
}

/** One arm of a host's `support` match: null [kind] or [module] matches any; the first matching rule wins. */
data class SupportRule(
    val kind: Kind?,
    val module: String?,
    val support: Support,
) {
    fun matches(
        k: Kind,
        m: String,
    ) = (kind == null || kind == k) && (module == null || module == m)
}

/** One host rtok installs into. [shared]: every variant reads the same files (Cursor), one apply covers them all. */
data class Host(
    val id: String,
    val variants: List<Variant>,
    private val rules: List<SupportRule>,
    val shared: Boolean = false,
) {
    fun support(
        kind: Kind,
        module: String,
    ): Support = rules.first { it.matches(kind, module) }.support

    /** True when no variant can have a hook installed, so only the MCP process can register its agent. */
    val hookless: Boolean get() = variants.all { support(it.kind, "hooks") is Support.No }
}

/** [HookSupport] backed by the host registry (link.rs `hookless`); an unknown host is not hookless. */
object HostHookSupport : HookSupport {
    override fun hookless(host: String) = Hosts.host(host)?.hookless == true
}

object Hosts {
    /** Every module an rtok install can carry, in print order. */
    val MODULES = listOf("hooks", "mcp", "proxy", "plugin")

    /** The host behind an id; null is refused by the CLI before any backup is taken. */
    fun host(id: String): Host? = ALL.firstOrNull { it.id == id }

    /** Whether [kind] is wanted given `--cli/--desktop/--all`; no flag (or `--all`) means every variant. */
    fun wants(
        kind: Kind,
        cli: Boolean,
        desktop: Boolean,
        all: Boolean,
    ) = all || (!cli && !desktop) || (kind == Kind.Cli && cli) || (kind == Kind.Desktop && desktop)

    /** Every host, in `agents list` order (rtok-config `HOSTS`). */
    val ALL: List<Host> =
        listOf(
            Host(
                "claude",
                listOf(
                    Variant(Kind.Cli, "Claude Code", listOf("claude"), listOf()),
                    Variant(Kind.Desktop, "Claude Desktop", listOf(), listOf("/Applications/Claude.app", "\$LOCALAPPDATA/AnthropicClaude/claude.exe")),
                ),
                listOf(
                    SupportRule(null, "mcp", Support.Yes),
                    SupportRule(Kind.Cli, "hooks", Support.Yes),
                    SupportRule(Kind.Cli, "proxy", Support.Flag("--proxy")),
                    SupportRule(Kind.Cli, null, Support.Yes),
                    SupportRule(Kind.Desktop, "hooks", Support.No("Claude Desktop has no hook events")),
                    SupportRule(Kind.Desktop, "proxy", Support.No("Claude Desktop has no base-URL setting; its requests do not pass through the proxy")),
                    SupportRule(Kind.Desktop, null, Support.No("Claude Desktop loads MCP from claude_desktop_config.json; there is no plugin directory to link")),
                ),
                shared = false,
            ),
            Host(
                "cursor",
                listOf(
                    Variant(Kind.Cli, "Cursor CLI", listOf("cursor-agent", "agent"), listOf()),
                    Variant(Kind.Desktop, "Cursor", listOf("cursor"), listOf("/Applications/Cursor.app", "\$LOCALAPPDATA/Programs/cursor/Cursor.exe", "/opt/Cursor")),
                ),
                listOf(
                    SupportRule(null, "hooks", Support.Yes),
                    SupportRule(null, "mcp", Support.Yes),
                    SupportRule(null, "plugin", Support.Yes),
                    SupportRule(null, null, Support.No("Cursor has no base-URL setting to point at the proxy")),
                ),
                shared = true,
            ),
            Host(
                "codex",
                listOf(
                    Variant(Kind.Cli, "Codex", listOf("codex"), listOf()),
                    Variant(Kind.Desktop, "Codex App", listOf(), listOf("/Applications/ChatGPT.app", "~/Applications/ChatGPT.app", "/Applications/Codex.app", "~/Applications/Codex.app")),
                ),
                listOf(
                    SupportRule(null, "mcp", Support.Yes),
                    SupportRule(null, "proxy", Support.Flag("--proxy")),
                    SupportRule(null, "hooks", Support.Yes),
                    SupportRule(null, "plugin", Support.Yes),
                    SupportRule(null, null, Support.No("Codex has no such module")),
                ),
                shared = true,
            ),
            Host(
                "opencode",
                listOf(
                    Variant(Kind.Cli, "OpenCode", listOf("opencode"), listOf()),
                    Variant(Kind.Desktop, "OpenCode Desktop", listOf("opencode-desktop"), listOf("/Applications/OpenCode.app", "\$LOCALAPPDATA/Programs/@opencode-aidesktop/OpenCode.exe", "\$LOCALAPPDATA/Programs/OpenCode/OpenCode.exe")),
                ),
                listOf(
                    SupportRule(null, "proxy", Support.Yes),
                    SupportRule(null, "mcp", Support.Yes),
                    SupportRule(null, "plugin", Support.Yes),
                    SupportRule(null, null, Support.No("OpenCode has no shell hook events; the linked plugin filters bash output instead")),
                ),
                shared = false,
            ),
            Host(
                "kilo",
                listOf(
                    Variant(Kind.Cli, "Kilo Code CLI", listOf("kilo"), listOf()),
                    Variant(Kind.Desktop, "Kilo Code for VS Code", listOf(), listOf("/Applications/Visual Studio Code.app", "\$LOCALAPPDATA/Programs/Microsoft VS Code/Code.exe")),
                ),
                listOf(
                    SupportRule(null, "mcp", Support.Yes),
                    SupportRule(null, "plugin", Support.Yes),
                    SupportRule(null, "hooks", Support.No("Kilo Code has no shell hook events; the linked plugin filters bash output instead")),
                    SupportRule(null, null, Support.No("Kilo Code keeps provider base URLs in its provider settings, which setup does not edit")),
                ),
                shared = true,
            ),
            Host(
                "pi",
                listOf(
                    Variant(Kind.Cli, "pi", listOf("pi"), listOf()),
                ),
                listOf(
                    SupportRule(null, "plugin", Support.Yes),
                    SupportRule(null, "hooks", Support.No("pi has no hook events; the extension owns the bash call path")),
                    SupportRule(null, "mcp", Support.No("pi philosophy is no MCP; registerTool is the plugin path when setup.pi.tools is true")),
                    SupportRule(null, null, Support.No("pi provider base URLs live in its models config, which setup does not edit")),
                ),
                shared = false,
            ),
            Host(
                "omp",
                listOf(
                    Variant(Kind.Cli, "oh my pi", listOf("omp"), listOf()),
                ),
                listOf(
                    SupportRule(null, "plugin", Support.Flag("--yes")),
                    SupportRule(null, "mcp", Support.Yes),
                    SupportRule(null, "hooks", Support.No("omp hooks are in-process TS modules; the linked pi extension owns that path")),
                    SupportRule(null, null, Support.No("omp provider base URLs live in models.yml, which setup does not edit")),
                ),
                shared = false,
            ),
            Host(
                "zcode",
                listOf(
                    Variant(Kind.Desktop, "ZCode", listOf(), listOf("/Applications/ZCode.app", "\$LOCALAPPDATA/Programs/ZCode/ZCode.exe")),
                ),
                listOf(
                    SupportRule(null, "hooks", Support.Yes),
                    SupportRule(null, "mcp", Support.Yes),
                    SupportRule(null, "plugin", Support.Yes),
                    SupportRule(null, null, Support.No("ZCode providers are per-id tables with their own keys and base URLs; setup does not edit them")),
                ),
                shared = false,
            ),
            Host(
                "kimi",
                listOf(
                    Variant(Kind.Cli, "Kimi Code", listOf("kimi"), listOf()),
                    Variant(Kind.Desktop, "Kimi Code Desktop", listOf(), listOf("/Applications/Kimi Code.app", "\$LOCALAPPDATA/Programs/Kimi Code/Kimi Code.exe")),
                ),
                listOf(
                    SupportRule(null, "hooks", Support.Yes),
                    SupportRule(null, "mcp", Support.Yes),
                    SupportRule(null, "plugin", Support.Offer("--yes")),
                    SupportRule(null, "proxy", Support.No("Kimi Code providers are [providers.<name>] tables with their own base_url and keys; setup does not edit them")),
                    SupportRule(null, null, Support.No("Kimi Code plugins live in plugins/managed/, owned by `kimi plugin install`; there is no local plugin directory to link")),
                ),
                shared = true,
            ),
            Host(
                "grok",
                listOf(
                    Variant(Kind.Cli, "Grok Build", listOf("grok"), listOf("~/.grok/bin/grok", "\$GROK_HOME/bin/grok")),
                ),
                listOf(
                    SupportRule(null, "plugin", Support.Offer("--yes")),
                    SupportRule(null, "mcp", Support.Yes),
                    SupportRule(null, "hooks", Support.No("Grok fires one hook set — the plugin's, or rtok's Claude hooks through [compat.claude] hooks — and setup adds no second set")),
                    SupportRule(null, null, Support.No("Grok Build providers live in its own settings tables; setup does not edit them")),
                ),
                shared = true,
            ),
            Host(
                "vscode",
                listOf(
                    Variant(Kind.Desktop, "VS Code", listOf("code"), listOf("/Applications/Visual Studio Code.app", "\$LOCALAPPDATA/Programs/Microsoft VS Code/Code.exe")),
                    Variant(Kind.Desktop, "VS Code - Insiders", listOf("code-insiders"), listOf("/Applications/Visual Studio Code - Insiders.app", "\$LOCALAPPDATA/Programs/Microsoft VS Code Insiders/Code - Insiders.exe")),
                ),
                listOf(
                    SupportRule(null, "mcp", Support.Yes),
                    SupportRule(null, "plugin", Support.Flag("--yes")),
                    SupportRule(null, "hooks", Support.No("no direct hooks file rtok writes; the linked plugin's hooks/hooks.json is what VS Code's Local harness runs (chat.useClaudeHooks)")),
                    SupportRule(null, "proxy", Support.No("Copilot in VS Code has no documented base-URL setting to point at the proxy")),
                    SupportRule(null, null, Support.No("VS Code loads MCP from the user mcp.json, or from the linked plugin")),
                ),
                shared = true,
            ),
            Host(
                "copilot",
                listOf(
                    Variant(Kind.Cli, "Copilot CLI", listOf("copilot"), listOf()),
                    Variant(Kind.Desktop, "GitHub Copilot", listOf(), listOf("/Applications/GitHub Copilot.app", "\$LOCALAPPDATA/Programs/GitHub Copilot/GitHub Copilot.exe")),
                ),
                listOf(
                    SupportRule(Kind.Cli, "hooks", Support.Yes),
                    SupportRule(null, "mcp", Support.Yes),
                    SupportRule(null, "hooks", Support.No("the GitHub Copilot app does not document hooks; the shared hooks/rtok.json is written for the CLI")),
                    SupportRule(null, "proxy", Support.No("Copilot BYOK is env-only (COPILOT_PROVIDER_BASE_URL); there is no config file to point at the proxy")),
                    SupportRule(Kind.Cli, "plugin", Support.Flag("--yes")),
                    SupportRule(null, "plugin", Support.No("the GitHub Copilot app does not document plugin installs; `copilot plugin` serves the CLI")),
                    SupportRule(null, null, Support.No("Copilot plugins live in installed-plugins/, owned by `copilot plugin`; there is no local plugin directory to link")),
                ),
                shared = true,
            ),
            Host(
                "commandcode",
                listOf(
                    Variant(Kind.Cli, "Command Code CLI", listOf("command-code", "cmd"), listOf()),
                ),
                listOf(
                    SupportRule(null, "hooks", Support.Yes),
                    SupportRule(null, "mcp", Support.Yes),
                    SupportRule(null, "plugin", Support.Yes),
                    SupportRule(null, null, Support.No("Command Code has no documented base-URL override for the proxy")),
                ),
                shared = true,
            ),
            Host(
                "aider",
                listOf(
                    Variant(Kind.Cli, "aider", listOf("aider"), listOf()),
                ),
                listOf(
                    SupportRule(null, "proxy", Support.Flag("--proxy")),
                    SupportRule(null, "hooks", Support.No("aider has no hook events; it reads .aider.conf.yml and .env")),
                    SupportRule(null, "mcp", Support.No("aider has no MCP support")),
                    SupportRule(null, null, Support.No("aider has no plugin directory to link")),
                ),
                shared = false,
            ),
            Host(
                "windsurf",
                listOf(
                    Variant(Kind.Desktop, "Windsurf", listOf(), listOf("/Applications/Windsurf.app", "\$LOCALAPPDATA/Programs/Windsurf/Windsurf.exe")),
                ),
                listOf(
                    SupportRule(null, "mcp", Support.Yes),
                    SupportRule(null, "hooks", Support.No("Cascade hooks speak agent_action_name/tool_info, not hook_event_name/tool_name; rtok hook has no --host windsurf mapping")),
                    SupportRule(null, "proxy", Support.No("Windsurf serves its own models; there is no documented base-URL setting to point at the proxy")),
                    SupportRule(null, null, Support.No("Cascade has no local plugin directory to link; rules and memories live in .windsurf/")),
                ),
                shared = false,
            ),
            Host(
                "zed",
                listOf(
                    Variant(Kind.Cli, "Zed CLI", listOf("zed"), listOf()),
                    Variant(Kind.Desktop, "Zed", listOf(), listOf("/Applications/Zed.app", "\$LOCALAPPDATA/Programs/Zed/Zed.exe")),
                ),
                listOf(
                    SupportRule(null, "mcp", Support.Yes),
                    SupportRule(null, "hooks", Support.No("Zed has no shell hook events; its agent runs tools itself and takes external agents over ACP")),
                    SupportRule(null, "proxy", Support.No("Zed serves hosted models or provider API keys; there is no documented base-URL setting to point at the proxy")),
                    SupportRule(null, null, Support.No("Zed extensions install from the marketplace; there is no local directory to link")),
                ),
                shared = true,
            ),
            Host(
                "cline",
                listOf(
                    Variant(Kind.Cli, "Cline CLI", listOf("cline"), listOf()),
                    Variant(Kind.Desktop, "Cline for VS Code", listOf(), listOf("/Applications/Visual Studio Code.app", "\$LOCALAPPDATA/Programs/Microsoft VS Code/Code.exe")),
                ),
                listOf(
                    SupportRule(null, "hooks", Support.Yes),
                    SupportRule(null, "mcp", Support.Yes),
                    SupportRule(null, "plugin", Support.Flag("--yes")),
                    SupportRule(null, null, Support.No("the Anthropic base URL is extension state, not a file rtok may edit")),
                ),
                shared = true,
            ),
            Host(
                "gemini",
                listOf(
                    Variant(Kind.Cli, "Gemini CLI", listOf("gemini"), listOf()),
                ),
                listOf(
                    SupportRule(null, "proxy", Support.No("Gemini CLI has no documented base-URL setting; its own HTTP_PROXY/HTTPS_PROXY covers MCP server transport only, not the model API")),
                    SupportRule(null, "hooks", Support.Yes),
                    SupportRule(null, "mcp", Support.Yes),
                    SupportRule(null, "plugin", Support.Flag("--yes")),
                    SupportRule(null, null, Support.No("unknown module")),
                ),
                shared = false,
            ),
            Host(
                "codewhale",
                listOf(
                    Variant(Kind.Cli, "CodeWhale", listOf("codewhale", "codew"), listOf()),
                ),
                listOf(
                    SupportRule(null, "hooks", Support.Yes),
                    SupportRule(null, "mcp", Support.Yes),
                    SupportRule(null, "proxy", Support.No("CodeWhale providers are [providers.<name>] tables with their own base_url and keys (docs/CONFIGURATION.md); setup does not edit them")),
                    SupportRule(null, null, Support.No("CodeWhale's plugin-bundle system needs a reviewed remote source and exact environment-source references (docs/PLUGIN_BUNDLES.md); there is no local directory to link")),
                ),
                shared = false,
            ),
            Host(
                "mimo",
                listOf(
                    Variant(Kind.Cli, "MiMo Code", listOf("mimo"), listOf()),
                    Variant(Kind.Desktop, "MiMo Desktop", listOf(), listOf("/Applications/Xiaomi MiMo AI.app", "~/Applications/Xiaomi MiMo AI.app")),
                ),
                listOf(
                    SupportRule(null, "mcp", Support.Yes),
                    SupportRule(null, "plugin", Support.Yes),
                    SupportRule(null, "hooks", Support.No("MiMo Code has no shell hook events; the linked plugin filters bash output instead")),
                    SupportRule(null, null, Support.No("MiMo Code has no documented base-URL override; MIMOCODE_HOME/MIMOCODE_CONFIG relocate config, not the model endpoint")),
                ),
                shared = true,
            ),
            Host(
                "antigravity",
                listOf(
                    Variant(Kind.Cli, "Antigravity CLI", listOf("agy"), listOf()),
                    Variant(Kind.Desktop, "Antigravity", listOf(), listOf("/Applications/Antigravity.app")),
                ),
                listOf(
                    SupportRule(Kind.Cli, "plugin", Support.Offer("--yes")),
                    SupportRule(Kind.Desktop, "plugin", Support.Flag("--yes")),
                    SupportRule(null, "hooks", Support.No("Antigravity's PreToolUse cannot rewrite tool input and PostToolUse cannot add context")),
                    SupportRule(null, "mcp", Support.No("rtok's MCP ships inside the plugin, the only install path")),
                    SupportRule(null, null, Support.No("Antigravity documents no model base-URL override")),
                ),
                shared = false,
            ),
            Host(
                "devin",
                listOf(
                    Variant(Kind.Cli, "Devin CLI", listOf("devin"), listOf()),
                    Variant(Kind.Desktop, "Devin", listOf(), listOf("/Applications/Devin.app", "\$LOCALAPPDATA/Programs/Devin/Devin.exe")),
                ),
                listOf(
                    SupportRule(null, "hooks", Support.Yes),
                    SupportRule(null, "mcp", Support.Yes),
                    SupportRule(null, "plugin", Support.Offer("--yes")),
                    SupportRule(null, "proxy", Support.No("Devin's proxy key is an HTTP proxy for CLI traffic, not a model API base URL")),
                    SupportRule(null, null, Support.No("not a Devin surface")),
                ),
                shared = true,
            ),
            Host(
                "roo",
                listOf(
                    Variant(Kind.Desktop, "Roo Code", listOf(), listOf("/Applications/Visual Studio Code.app", "\$LOCALAPPDATA/Programs/Microsoft VS Code/Code.exe")),
                ),
                listOf(
                    SupportRule(null, "mcp", Support.Yes),
                    SupportRule(null, "hooks", Support.No("Roo Code's docs describe mcp_settings.json and .roo/mcp.json, not shell hook events")),
                    SupportRule(null, "proxy", Support.No("Roo Code has no documented model base-URL file; roo-cline.debugProxy is a debug proxy, not the model endpoint")),
                    SupportRule(null, null, Support.No("Roo Code has no documented local plugin directory to link; modes live in the extension")),
                ),
                shared = false,
            ),
            Host(
                "qwen",
                listOf(
                    Variant(Kind.Cli, "Qwen Code", listOf("qwen"), listOf()),
                ),
                listOf(
                    SupportRule(null, "hooks", Support.Yes),
                    SupportRule(null, "mcp", Support.Yes),
                    SupportRule(null, "plugin", Support.Flag("--yes")),
                    SupportRule(null, "proxy", Support.No("Qwen Code's model.baseUrl is written by the model picker; modelProviders entries carry their own baseUrl, which setup does not edit")),
                    SupportRule(null, null, Support.No("unknown module")),
                ),
                shared = false,
            ),
            Host(
                "codebuddy",
                listOf(
                    Variant(Kind.Cli, "CodeBuddy Code", listOf("codebuddy"), listOf()),
                    Variant(Kind.Desktop, "WorkBuddy", listOf(), listOf("/Applications/WorkBuddy.app", "~/Applications/WorkBuddy.app", "\$LOCALAPPDATA/Programs/WorkBuddy")),
                ),
                listOf(
                    SupportRule(null, "hooks", Support.Yes),
                    SupportRule(null, "mcp", Support.Yes),
                    SupportRule(null, "plugin", Support.Flag("--yes")),
                    SupportRule(null, "proxy", Support.No("CodeBuddy Code takes its endpoint from the CODEBUDDY_BASE_URL environment variable; there is no config file to point at the proxy")),
                    SupportRule(null, null, Support.No("unknown module")),
                ),
                shared = false,
            ),
            Host(
                "goose",
                listOf(
                    Variant(Kind.Cli, "Goose CLI", listOf("goose"), listOf()),
                    Variant(Kind.Desktop, "Goose", listOf(), listOf("/Applications/Goose.app")),
                ),
                listOf(
                    SupportRule(null, "mcp", Support.Yes),
                    SupportRule(null, "hooks", Support.No("Goose runs hooks from Open Plugins directories with its own event names, tool names and decision shape; rtok has no adapter for that payload yet")),
                    SupportRule(null, "proxy", Support.No("Goose documents its provider endpoint as the GOOSE_PROVIDER__HOST environment variable only; there is no file to point at the proxy")),
                    SupportRule(null, null, Support.No("Goose plugins are Open Plugins directories (plugin.json, hooks/hooks.json); rtok ships no bundle in that format yet")),
                ),
                shared = false,
            ),
            Host(
                "droid",
                listOf(
                    Variant(Kind.Cli, "Factory Droid", listOf("droid"), listOf()),
                ),
                listOf(
                    SupportRule(null, "mcp", Support.Yes),
                    SupportRule(null, "hooks", Support.No("Droid documents hooks, but rtok has no checked mapping of its tool names and PreToolUse output yet")),
                    SupportRule(null, "proxy", Support.No("Droid has no single model base-URL setting; each customModels entry carries its own baseUrl")),
                    SupportRule(null, null, Support.No("Droid plugins install into its own cache through `droid plugin install`; rtok ships no Droid plugin")),
                ),
                shared = false,
            ),
            Host(
                "kiro",
                listOf(
                    Variant(Kind.Cli, "Kiro CLI", listOf("kiro-cli"), listOf()),
                    Variant(Kind.Desktop, "Kiro IDE", listOf(), listOf("/Applications/Kiro.app", "~/Applications/Kiro.app")),
                ),
                listOf(
                    SupportRule(null, "mcp", Support.Yes),
                    SupportRule(null, "hooks", Support.No("Kiro's hooks page does not state the exit-code or output-to-context rules of command hooks")),
                    SupportRule(null, "proxy", Support.No("Kiro documents no model base-URL setting")),
                    SupportRule(null, null, Support.No("Kiro documents no local plugin directory to link")),
                ),
                shared = true,
            ),
            Host(
                "junie",
                listOf(
                    Variant(Kind.Cli, "Junie CLI", listOf("junie"), listOf()),
                ),
                listOf(
                    SupportRule(null, "mcp", Support.Yes),
                    SupportRule(null, "hooks", Support.No("Junie documents only a SessionStart hook, and rtok's hooks need tool-use events")),
                    SupportRule(null, "proxy", Support.No("Junie has no documented model base-URL file")),
                    SupportRule(null, null, Support.No("Junie has no documented local plugin directory to link")),
                ),
                shared = false,
            ),
            Host(
                "amp",
                listOf(
                    Variant(Kind.Cli, "Amp", listOf("amp"), listOf()),
                ),
                listOf(
                    SupportRule(null, "mcp", Support.Yes),
                    SupportRule(null, "hooks", Support.No("Amp has no shell hook events; its plugin events (tool.call, tool.result) are TypeScript handlers")),
                    SupportRule(null, "proxy", Support.No("amp.url is Amp's own server URL, not a model-provider base URL")),
                    SupportRule(null, null, Support.No("Amp loads .ts and .js plugins from its plugins directory, but rtok ships no plugin for its API yet")),
                ),
                shared = false,
            ),
            Host(
                "warp",
                listOf(
                    Variant(Kind.Desktop, "Warp", listOf(), listOf("/Applications/Warp.app")),
                ),
                listOf(
                    SupportRule(null, "mcp", Support.Yes),
                    SupportRule(null, "hooks", Support.No("Warp documents no hook surface")),
                    SupportRule(null, "proxy", Support.No("Warp documents no setting that points its agent at another endpoint")),
                    SupportRule(null, null, Support.No("Warp documents no plugin format; rtok ships no Warp plugin")),
                ),
                shared = false,
            ),
            Host(
                "continue",
                listOf(
                    Variant(Kind.Cli, "Continue CLI", listOf("cn"), listOf()),
                    Variant(Kind.Desktop, "Continue", listOf(), listOf("/Applications/Visual Studio Code.app", "\$LOCALAPPDATA/Programs/Microsoft VS Code/Code.exe")),
                ),
                listOf(
                    SupportRule(null, "mcp", Support.Yes),
                    SupportRule(null, "hooks", Support.No("Continue's docs describe config.yaml, mcpServers and rules, not shell hook events")),
                    SupportRule(null, "proxy", Support.No("Continue sets a model's apiBase per model in config.yaml; there is no single endpoint file to point at the proxy")),
                    SupportRule(null, null, Support.No("Continue has no documented local plugin directory to link; blocks and rules live in config.yaml and .continue/")),
                ),
                shared = false,
            ),
            Host(
                "crush",
                listOf(
                    Variant(Kind.Cli, "Crush", listOf("crush"), listOf()),
                ),
                listOf(
                    SupportRule(null, "mcp", Support.Yes),
                    SupportRule(null, "hooks", Support.No("Crush documents only a PreToolUse hook, and rtok has no checked mapping of its tool names")),
                    SupportRule(null, "proxy", Support.No("Crush takes provider base URLs per provider entry; rtok has no checked single endpoint to point at the proxy")),
                    SupportRule(null, null, Support.No("Crush has no documented local plugin directory to link; it loads skills, not plugins")),
                ),
                shared = false,
            ),
            Host(
                "openhands",
                listOf(
                    Variant(Kind.Cli, "OpenHands", listOf("openhands"), listOf()),
                ),
                listOf(
                    SupportRule(null, "mcp", Support.Yes),
                    SupportRule(null, "hooks", Support.No("OpenHands documents hooks only as a project `.openhands/hooks.json`; no user-level file is documented")),
                    SupportRule(null, "proxy", Support.No("rtok has not checked OpenHands's LLM base-URL setting, so it writes none")),
                    SupportRule(null, null, Support.No("rtok ships no OpenHands plugin")),
                ),
                shared = false,
            ),
            Host(
                "augment",
                listOf(
                    Variant(Kind.Cli, "Augment Auggie CLI", listOf("auggie"), listOf()),
                ),
                listOf(
                    SupportRule(null, "mcp", Support.Yes),
                    SupportRule(null, "hooks", Support.No("Augment hooks must be script files and PreToolUse can only deny (no updatedInput), so rtok cannot rewrite a call through them")),
                    SupportRule(null, "proxy", Support.No("Augment documents no model endpoint setting or bring-your-own key to point at the proxy")),
                    SupportRule(null, null, Support.No("Auggie plugins come from marketplaces through `auggie plugin install`; rtok ships no Augment plugin")),
                ),
                shared = false,
            ),
            Host(
                "reasonix",
                listOf(
                    Variant(Kind.Cli, "Reasonix", listOf("reasonix", "dsnix"), listOf()),
                ),
                listOf(
                    SupportRule(null, "mcp", Support.Yes),
                    SupportRule(null, "hooks", Support.No("Reasonix hooks (settings.json) use their own event payload and tool names, which rtok has no checked mapping for")),
                    SupportRule(null, "proxy", Support.No("Reasonix has no single endpoint setting; each [[providers]] entry carries its own base_url")),
                    SupportRule(null, null, Support.No("Reasonix plugin packages are its own format; rtok ships no Reasonix plugin")),
                ),
                shared = false,
            ),
        )
}
