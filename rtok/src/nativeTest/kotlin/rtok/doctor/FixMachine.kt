// The in-memory machine of src/doctor/fix.rs's tests (`Machine`, `cfg`, `machine`, `put`, `text` and the shared
// settings fixtures), used by the fix and the doctor web tests.
package rtok.doctor

import rtok.config.Config
import rtok.sys.IoException

/** An in-memory machine whose files the [Writer] side can change. */
@Suppress("TooManyFunctions")
internal class FixMachine(
    val files: MutableMap<String, String> = linkedMapOf(),
    var failBackup: Boolean = false,
    var failWrite: Boolean = false,
) : ProbeFs,
    ProbeEnv,
    Which,
    Writer {
    /** Rewrites the file when it is read for the [raceAt]-th time (an editor saving between the plan and the write). */
    var race: Pair<String, String>? = null
    var raceAt = 0
    var reads = 0
    val backups = mutableListOf<String>()

    override fun canonical(path: String) = path

    override fun read(path: String): String {
        reads += 1
        race?.let { (p, body) -> if (p == path && reads == raceAt) files[p] = body }
        return files[path] ?: throw ReadError(true, "not found")
    }

    override fun kind(path: String): PathKind = if (path in files) PathKind.File(true) else PathKind.Missing

    override fun variable(name: String): String? = null

    override fun home() = "/h"

    override fun cwd() = "/proj"

    override fun find(program: String): String? = null

    override fun backup(
        path: String,
        keep: Int,
    ): String {
        if (failBackup) throw IoException(IoException.Kind.Other, "Permission denied")
        return "/h/.backup_configs/$path.bak".also { backups += it }
    }

    override fun write(
        path: String,
        body: String,
    ) {
        if (failWrite) throw IoException(IoException.Kind.Other, "Permission denied")
        files[path] = body
    }

    fun probes() = Probes(this, this, this)

    fun put(
        path: String,
        body: String,
    ) {
        files[path] = body
    }

    fun text(path: String) = files[path].orEmpty()

    companion object {
        const val SETTINGS = "/h/.claude/settings.json"

        /** The same valid hook in the user's and in the project's settings. */
        const val USER_DUP = """{
  // mine
  "theme": "dark",
  "hooks": {"Stop": [{"hooks": [{"type": "command", "command": "/h/.claude/settings.json"}]}]}
}
"""
        const val PROJECT_DUP = """{"hooks": {"Stop": [{"hooks": [{"type": "command", "command": "/h/.claude/settings.json"}]}]}}"""
        const val BROKEN = """{"hooks": {"Stop": [{"hooks": [{"type": "command", "command": "/h/gone/old.sh"}]}]}}"""

        fun machine(settings: String) = FixMachine(linkedMapOf(SETTINGS to settings))

        fun cfg(): Config {
            val c = Config()
            return c.copy(
                doctor = c.doctor.copy(settingsPath = SETTINGS, claudeJson = "/h/.claude.json"),
                setup =
                    c.setup.copy(
                        claude = c.setup.claude.copy(settingsPath = SETTINGS),
                        cursor = c.setup.cursor.copy(hooksPath = "/h/.cursor/hooks.json"),
                        gemini = c.setup.gemini.copy(dir = "/h/.gemini"),
                        codex = c.setup.codex.copy(configPath = "/h/.codex/config.toml"),
                    ),
            )
        }
    }
}
