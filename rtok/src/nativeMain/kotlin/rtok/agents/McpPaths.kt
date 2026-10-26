// Per-host MCP config paths (each host folder's `mcp_path` / `settings_path` / `config_path` / `desktop_path`
// that agents/mcp.rs `surfaces` reads). One place for every host's file, so the surfaces table stays data only.
package rtok.agents

import rtok.config.Config
import rtok.config.envUserHome
import rtok.sys.exists
import kotlin.experimental.ExperimentalNativeApi

/** The host config files rtok's MCP entry lives in, resolved from `[setup]` (and the OS defaults). */
object McpPaths {
    /** The home dir every OS-default path hangs under (`$HOME`; empty when unset). */
    fun home(): String = envUserHome().orEmpty()

    @OptIn(ExperimentalNativeApi::class)
    val macos = Platform.osFamily == OsFamily.MACOSX

    /** OS-default VS Code user profile dir (vscode `default_user_dir`). */
    fun vscodeDefaultUserDir(insiders: Boolean): String {
        val app = if (insiders) "Code - Insiders" else "Code"
        return if (macos) "${home()}/Library/Application Support/$app/User" else "${home()}/.config/$app/User"
    }

    fun vscodeUserDir(
        cfg: Config,
        insiders: Boolean,
    ): String {
        val base = if (insiders) cfg.setup.vscode.insidersUserDir else cfg.setup.vscode.codeUserDir
        return base.ifEmpty { vscodeDefaultUserDir(insiders) }
    }

    fun codeUserDir(cfg: Config) =
        cfg.setup.vscode.codeUserDir
            .ifEmpty { vscodeDefaultUserDir(false) }

    fun claudeDesktop(): String =
        if (macos) {
            "${home()}/Library/Application Support/Claude/claude_desktop_config.json"
        } else {
            "${home()}/.config/Claude/claude_desktop_config.json"
        }

    /** CodeBuddy reads the first existing of these; with none, `.mcp.json` is created. */
    fun codebuddy(
        cfg: Config,
        kind: Kind,
    ): String {
        val d = if (kind == Kind.Cli) cfg.setup.codebuddy.dir else cfg.setup.codebuddy.workbuddyDir
        val candidates = mutableListOf(join(d, ".mcp.json"), join(d, "mcp.json"))
        if (kind == Kind.Cli && d == "${home()}/.codebuddy") candidates += "${home()}/.codebuddy.json"
        return candidates.firstOrNull(::exists) ?: join(d, ".mcp.json")
    }

    fun opencodeDesktop(): String =
        if (macos) {
            "${home()}/Library/Application Support/ai.opencode.desktop/opencode.json"
        } else {
            "${home()}/.config/ai.opencode.desktop/opencode.json"
        }
}
