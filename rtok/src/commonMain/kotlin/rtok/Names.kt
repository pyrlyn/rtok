// Port of src/names.rs — command and tool names shared by surfaces and plugins.
package rtok

/** Basename of a command path; splits on `/` and `\`, drops a trailing `.exe` (case-insensitive). */
fun cmdStem(path: String): String {
    val base = path.substring(maxOf(path.lastIndexOf('/'), path.lastIndexOf('\\')) + 1)
    return if (base.length >= 4 && base.takeLast(4).equals(".exe", ignoreCase = true)) base.dropLast(4) else base
}

const val POSIX_RUN_GUARD = "command -v rtok >/dev/null 2>&1 || rtok() ( for a; do :; done; eval \"\$a\" ); "

/** Leads the `rtok run` wrapper on POSIX (never on Windows). */
fun runGuard(windows: Boolean = false): String = if (windows) "" else POSIX_RUN_GUARD

/** PreToolUse sees the user's command; PostToolUse often sees `rtok run -- '…'`. */
fun stripWrap(s0: String): String {
    val s = stripRunWrap(s0)
    if (s.length >= 2 && s.startsWith('\'') && s.endsWith('\'')) {
        val inner = s.substring(1, s.length - 1)
        return if (inner.contains("'\"'\"'")) inner.replace("'\"'\"'", "'") else inner.replace("''", "'")
    }
    return s
}

private fun stripRunWrap(s0: String): String {
    val s = s0.removePrefix(POSIX_RUN_GUARD)
    if (s.startsWith("rtok run -- ")) return s.removePrefix("rtok run -- ")
    if (!s.startsWith("rtok run --agent ")) return s
    val after = s.removePrefix("rtok run --agent ")
    val sp = after.indexOf(' ')
    if (sp < 0) return s
    val id = after.substring(0, sp)
    val rest = after.substring(sp + 1)
    return if (wrapAgentId(id) && rest.startsWith("-- ")) rest.removePrefix("-- ") else s
}

private fun wrapAgentId(id: String) = id.length in 1..64 && id.all { it.code < 128 && (it.isLetterOrDigit() || it == '_' || it == '-') }

/** Shortest archive-id prefix `rtok expand` resolves. */
const val MIN_PREFIX = 8

fun shortId(id: String) = id.take(MIN_PREFIX)

/** Host tool names (`bash`, `read_file`, `edit`, …) to the Claude names `plugins::guard` matches. */
fun canonicalToolName(name: String): String {
    val l = name.lowercase()
    return when {
        l == "exec" || l == "run_commands" || listOf("bash", "shell", "terminal", "powershell").any { l.contains(it) } -> "Bash"
        l.startsWith("read") || l.startsWith("view") -> "Read"
        l == "edit" || l == "replace" || l == "edit_file" -> "Edit"
        l == "write" || l == "write_file" -> "Write"
        else -> name
    }
}
