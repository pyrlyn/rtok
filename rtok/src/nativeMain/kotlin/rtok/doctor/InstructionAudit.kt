// Port of the instruction audit of src/doctor.rs (T7.2): the user and project instruction files plus the skill or
// MCP text of known context injectors, their token cost, and the long lines repeated across them.
package rtok.doctor

import kotlinx.serialization.json.JsonElement
import rtok.config.Config
import rtok.config.ProjectRoot
import rtok.config.envUserHome
import rtok.estimate
import rtok.plugin.sdk.Class
import rtok.plugins.guard.skillMdPath
import rtok.sys.canon
import rtok.sys.isFile
import rtok.sys.parent
import rtok.sys.readToString
import rtok.sys.samePath

internal object InstructionAudit {
    val INJECTORS = listOf("lean-ctx", "engram", "ponytail", "claude-mem", "token-optimizer", "caveman", "headroom")

    private const val MIN_LINE = 40
    private const val SHOWN = 60

    class Source(
        val name: String,
        val path: String,
        val text: String,
    )

    fun run(
        cfg: Config,
        settings: JsonElement?,
        claude: JsonElement?,
        cwd: String?,
    ): Instructions {
        val srcs = mutableListOf<Source>()
        parent(cfg.doctor.settingsPath)?.let { pushFile(srcs, "claude-user", "$it/CLAUDE.md") }
        cwd?.let { pushProjectFiles(srcs, it) }
        val blob = "${settings?.toString().orEmpty()}${claude?.toString().orEmpty()}"
        for (name in INJECTORS.filter { it in blob }) {
            val path = findSkill(name) ?: "mcp:$name"
            srcs += Source(name, path, runCatching { readToString(path) }.getOrDefault(""))
        }
        val warnAt = cfg.doctor.instructionWarnTokens.toInt()
        return Instructions(
            rows =
                srcs.map {
                    val tokens = estimate(it.text, Class.Prose, cfg.estimator)
                    InstructionRow(it.name, tokens, it.path, tokens > warnAt)
                },
            duplicates = duplicates(srcs),
        )
    }

    /** By the file itself, not its name: `CLAUDE.md -> AGENTS.md` is one source. */
    fun pushFile(
        srcs: MutableList<Source>,
        name: String,
        path: String,
    ) {
        val text = runCatching { readToString(path) }.getOrNull() ?: return
        val me = canon(path)
        if (srcs.any { samePath(canon(it.path), me) }) return
        srcs += Source(name, path, text)
    }

    /** `checkout`, not `main`: a linked worktree loads the instructions checked out there. */
    fun pushProjectFiles(
        srcs: MutableList<Source>,
        cwd: String,
    ) {
        val root = ProjectRoot.find(cwd) ?: return
        pushFile(srcs, "claude-project", "${root.checkout}/CLAUDE.md")
        pushFile(srcs, "agents-project", "${root.checkout}/AGENTS.md")
    }

    private fun findSkill(name: String): String? = envUserHome()?.let { skillMdPath(it, name) }?.takeIf(::isFile)

    /** Lines of 40+ chars found in more than one source, cut to 60 chars, with the sources' names. */
    fun duplicates(srcs: List<Source>): List<Pair<String, List<String>>> {
        val map = LinkedHashMap<String, MutableList<String>>()
        for (s in srcs) {
            for (line in s.text.lines()) {
                val t = line.trim()
                if (t.encodeToByteArray().size < MIN_LINE) continue
                map.getOrPut(t) { mutableListOf() } += s.name
            }
        }
        return map.sortedByKey().mapNotNull { (sent, names) ->
            val n = names.distinct().sorted()
            if (n.size > 1) takeChars(sent, SHOWN) to n else null
        }
    }

    private fun takeChars(
        s: String,
        n: Int,
    ): String {
        var i = 0
        var count = 0
        while (i < s.length && count < n) {
            i += if (s[i].isHighSurrogate() && i + 1 < s.length) 2 else 1
            count++
        }
        return s.substring(0, i)
    }
}
