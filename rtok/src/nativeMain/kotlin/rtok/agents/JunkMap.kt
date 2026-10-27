// Port of src/agents/junk_map.rs: the host junk map of `research.md` §22 (T182, D36), encoded once (T330.2). Per host
// it names the folders the host writes: the temp, log and cache paths §22 documents (`documented`), its config/data
// home, and the platform folders of a desktop app §22 has no row for. Only a `documented` path may ever be cleared;
// the rest is listed read-only. A template starts with a token (`{home}`, `{claude}`, `{xdg_cache}`, …) so an
// environment override moves every path under it.
package rtok.agents

import rtok.config.envUserHome
import rtok.config.envVar
import rtok.sys.join
import kotlin.experimental.ExperimentalNativeApi

enum class JunkRole(
    val word: String,
) {
    /** The host's config or data home. Never junk as a whole (§22). */
    Data("data"),
    Temp("temp"),
    Logs("logs"),
    Cache("cache"),
}

data class JunkSpec(
    val role: JunkRole,
    val path: String,
    /** A row of §22 (official docs or the host's own source), not a guess. */
    val documented: Boolean,
    /** One subfolder per host build (VS Code's `CachedData/<commit>`): only stale builds are cache. */
    val perCommit: Boolean = false,
)

object JunkMap {
    /** Hosts with no [specs] row on purpose: §22 reads "not documented" for every path they write. */
    val NO_ROWS =
        listOf("kilo", "aider", "roo", "droid", "goose", "kiro", "junie", "amp", "warp", "continue", "crush", "openhands", "augment", "reasonix")

    /** Electron's `sessionData` mixes these with cookies and localStorage, so only these subfolders can be cache (§22.2). */
    private val ELECTRON_CACHES = listOf("Cache", "Code Cache", "GPUCache", "CachedData", "DawnCache")

    private fun doc(
        role: JunkRole,
        path: String,
    ) = JunkSpec(role, path, documented = true)

    private fun guess(
        role: JunkRole,
        path: String,
    ) = JunkSpec(role, path, documented = false)

    /** Where the platform keeps an Electron app's data and caches; all listed read-only. */
    private fun electron(app: String): List<JunkSpec> {
        val data = listOf("{home}/Library/Application Support/$app", "{xdg_config}/$app")
        return data.map { guess(JunkRole.Data, it) } +
            guess(JunkRole.Cache, "{home}/Library/Caches/$app") +
            guess(JunkRole.Cache, "{xdg_cache}/$app") +
            data.flatMap { d -> ELECTRON_CACHES.map { guess(JunkRole.Cache, "$d/$it") } }
    }

    private val ROWS: Map<String, List<JunkSpec>> =
        run {
            val data = JunkRole.Data
            val temp = JunkRole.Temp
            val logs = JunkRole.Logs
            val cache = JunkRole.Cache
            mapOf(
                "claude" to listOf(doc(data, "{claude}"), doc(temp, "{claude}/shell-snapshots"), doc(logs, "{claude}/debug"), doc(cache, "{claude}/paste-cache")),
                "codex" to listOf(doc(data, "{codex}"), doc(logs, "{codex}/log")),
                "opencode" to listOf(doc(data, "{xdg_data}/opencode"), doc(logs, "{xdg_data}/opencode/log"), doc(cache, "{xdg_cache}/opencode")),
                "pi" to listOf(doc(data, "{home}/.pi/agent"), doc(logs, "{home}/.pi/agent/pi-debug.log")),
                "omp" to listOf(doc(data, "{home}/.omp"), doc(cache, "{home}/.omp/cache/github-cache.db")),
                "zcode" to listOf(doc(data, "{home}/.zcode"), doc(temp, "{home}/.zcode/cli/exec")),
                "kimi" to
                    listOf(
                        doc(data, "{home}/.kimi-code"),
                        doc(logs, "{home}/.kimi-code/logs"),
                        doc(cache, "{home}/.kimi-code/bin"),
                        doc(cache, "{home}/.kimi-code/updates/latest.json"),
                    ),
                "grok" to listOf(doc(data, "{home}/.grok"), doc(logs, "{home}/.grok/logs")),
                "copilot" to listOf(doc(data, "{home}/.copilot"), doc(logs, "{home}/.copilot/logs"), doc(cache, "{copilot_cache}")),
                "zed" to
                    listOf(
                        doc(data, "{home}/Library/Application Support/Zed"),
                        doc(logs, "{home}/Library/Logs/Zed/Zed.log"),
                        doc(logs, "{xdg_data}/zed/logs/Zed.log"),
                        doc(cache, "{home}/Library/Caches/Zed"),
                        doc(cache, "{xdg_cache}/zed"),
                    ),
                "gemini" to listOf(doc(data, "{home}/.gemini")),
                "codewhale" to listOf(doc(data, "{home}/.codewhale"), doc(cache, "{home}/.codewhale/update-check.json")),
                "commandcode" to listOf(doc(data, "{home}/.commandcode")),
                "cline" to listOf(doc(data, "{home}/.cline"), doc(logs, "{home}/.cline/data/logs")),
                "mimo" to
                    listOf(
                        doc(data, "{home}/.config/mimocode"),
                        doc(data, "{home}/.local/share/mimocode"),
                        doc(logs, "{home}/.local/share/mimocode/log"),
                        doc(cache, "{home}/.cache/mimocode"),
                    ),
                "devin" to listOf(doc(data, "{home}/.config/devin"), doc(logs, "{home}/.local/share/devin/cli/logs")),
                "qwen" to listOf(doc(data, "{home}/.qwen")),
                "codebuddy" to listOf(doc(data, "{home}/.codebuddy"), doc(data, "{home}/.workbuddy")),
                // The per-commit rows come before `electron`, which lists the same folders read-only: the first spec wins.
                "vscode" to
                    listOf(
                        doc(data, "{xdg_config}/Code"),
                        doc(cache, "{home}/Library/Application Support/Code/CachedData").copy(perCommit = true),
                        doc(cache, "{xdg_config}/Code/CachedData").copy(perCommit = true),
                    ) + electron("Code"),
                "cursor" to electron("Cursor"),
                "windsurf" to electron("Windsurf"),
                "antigravity" to electron("Antigravity"),
            )
        }

    /** The §22 rows and desktop-app folders of [host]; empty for a host §22 documents nothing for. */
    fun specs(host: String): List<JunkSpec> = ROWS[host].orEmpty()
}

/**
 * The roots the templates start from, read from the environment once so a test can build its own. [get] answers an
 * environment variable; an empty or relative value is ignored, as the XDG spec says for its own variables.
 */
@OptIn(ExperimentalNativeApi::class)
class JunkRoots(
    val home: String,
    get: (String) -> String?,
    macos: Boolean = Platform.osFamily == OsFamily.MACOSX,
) {
    private val roots: Map<String, String>

    init {
        fun dir(
            v: String,
            default: String,
        ) = get(v)?.takeIf { it.startsWith('/') } ?: default
        val xdgCache = dir("XDG_CACHE_HOME", join(home, ".cache"))
        roots =
            mapOf(
                "home" to home,
                "claude" to dir("CLAUDE_CONFIG_DIR", join(home, ".claude")),
                "codex" to dir("CODEX_HOME", join(home, ".codex")),
                "xdg_cache" to xdgCache,
                "xdg_config" to dir("XDG_CONFIG_HOME", join(home, ".config")),
                "xdg_data" to dir("XDG_DATA_HOME", join(home, ".local/share")),
                "copilot_cache" to dir("COPILOT_CACHE_HOME", if (macos) join(home, "Library/Caches/copilot") else join(xdgCache, "copilot")),
                "rtok_cache" to if (macos) join(home, "Library/Caches/rtok") else join(xdgCache, "rtok"),
            )
    }

    companion object {
        fun fromEnv() = JunkRoots(envUserHome().orEmpty(), ::envVar)
    }

    /** `{token}` or `{token}/rest` as a path; a template without a known token is returned as written. */
    fun resolve(template: String): String {
        val close = template.indexOf('}')
        val base = if (template.startsWith('{') && close > 0) roots[template.substring(1, close)] else null
        base ?: return template
        val rest = template.substring(close + 1)
        return when {
            rest.startsWith('/') -> join(base, rest.substring(1))
            else -> base
        }
    }
}
