// Port of the command half of src/doctor/hooks.rs (`shell_syntax`, `expand`, `split_words`, `outside_quotes`,
// `percent_vars`, `is_abs_windows`, `base_name`, `target`, `normalize`, `classify`, `verdict`, `classify_windows`,
// `unmounted_volume`): a hook command resolved without running it. The command is split like the host shell, variables
// are expanded, and the target is the first word, the script of a known interpreter, or a program on `PATH`.
package rtok.doctor

import kotlin.experimental.ExperimentalNativeApi

/** What a hook command may rely on besides the machine. */
internal data class Scope(
    val project: String,
    /** `${CLAUDE_PLUGIN_ROOT}`, known only inside a plugin's own `hooks.json`. */
    val pluginRoot: String? = null,
    /**
     * Whether a relative path resolves against [project]. Only Claude Code documents that; for any other host a
     * relative path is not judged, since guessing its base would offer a working hook for removal.
     */
    val relativeOk: Boolean,
)

internal sealed class Verdict {
    data object Ok : Verdict()

    data class Broken(
        val why: String,
    ) : Verdict()

    data class Suspect(
        val why: String,
    ) : Verdict()

    data class Unverified(
        val why: String,
    ) : Verdict()
}

@Suppress("TooManyFunctions")
internal object HookCommand {
    /** Programs that run a script given as an argument. */
    private val INTERPRETERS = setOf("bash", "sh", "zsh", "node", "python", "python3", "deno", "bun", "ruby", "pwsh", "tsx")

    /** Shell builtins a hook may name directly; they need no file. */
    private val BUILTINS =
        setOf(":", ".", "[", "cd", "command", "echo", "exit", "export", "false", "printf", "pwd", "read", "set", "source", "test", "true", "type", "unset")

    /** Programs `cmd` runs without a file, besides the POSIX [BUILTINS] the Git Bash hooks share. */
    private val CMD_BUILTINS = setOf("call", "copy", "del", "dir", "md", "mkdir", "move", "rd", "ren", "rem", "start", "title", "ver")

    private val INLINE_FLAGS = setOf("-c", "-e", "--eval", "-p", "--print")
    private const val PLUGIN_ROOT = "CLAUDE_PLUGIN_ROOT"
    private const val NO_BASE = "relative path: this host's base directory is not known"
    private const val VOLUME_PARTS = 3 // `/`, `Volumes`, the volume
    private const val NO_SCRIPT = "no script argument"

    /** Syntax a static check cannot judge: substitution, operators, subshells, line breaks. */
    fun shellSyntax(raw: String): String? =
        when {
            raw.contains("$(") || raw.contains('`') -> "command substitution"
            raw.any { it in "|&;<>()\n" } -> "shell operators"
            raw.split(WS).firstOrNull { it.isNotEmpty() } == "eval" -> "eval"
            else -> null
        }

    private val WS = Regex("\\s+")

    private fun isNameChar(c: Char) = (c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9') || c == '_'

    /** The outcome of [expand]: the word, or the name of what could not be expanded. */
    sealed class Expanded {
        data class Word(
            val text: String,
        ) : Expanded()

        data class Unset(
            val name: String,
        ) : Expanded()
    }

    /** [word] with `~`, `$NAME` and `${NAME}` expanded; [Expanded.Unset] names an unset variable, `HOME` or `$`. */
    @Suppress("ReturnCount", "CyclomaticComplexMethod") // one branch per Rust arm
    fun expand(
        word: String,
        p: Probes,
        scope: Scope,
    ): Expanded {
        val w = if (p.env.windows()) percentVars(word) else word
        val out = StringBuilder()
        val rest =
            if (w == "~" || w.startsWith("~/")) {
                out.append(p.env.home() ?: return Expanded.Unset("HOME"))
                w.substring(1)
            } else {
                w
            }
        var i = 0
        while (i < rest.length) {
            val c = rest[i]
            if (c != '$') {
                out.append(c)
                i++
                continue
            }
            val tail = rest.substring(i + 1)
            val name: String
            val len: Int
            if (tail.startsWith('{')) {
                val end = tail.indexOf('}', 1)
                if (end < 0) return Expanded.Unset("$")
                name = tail.substring(1, end)
                len = end + 1
            } else {
                val end = tail.indexOfFirst { !isNameChar(it) }.let { if (it < 0) tail.length else it }
                name = tail.substring(0, end)
                len = end
            }
            if (name.isEmpty() || name[0].isDigit()) return Expanded.Unset("$")
            val value =
                when (name) {
                    "HOME" -> p.env.home()
                    "CLAUDE_PROJECT_DIR" -> scope.project
                    PLUGIN_ROOT -> scope.pluginRoot
                    else -> p.env.variable(name)
                }
            out.append(value ?: return Expanded.Unset(name))
            i += 1 + len
        }
        return Expanded.Word(out.toString())
    }

    /**
     * [raw] split into words by the host shell's rules. POSIX by default; with [win], `"` groups, `'` and `\` are plain
     * characters except a `\` run right before a `"` (the `CommandLineToArgvW` rule), so an unquoted `C:\tools\x.cmd`
     * stays one intact word.
     */
    @Suppress("ReturnCount", "CyclomaticComplexMethod") // one branch per Rust arm
    fun splitWords(
        raw: String,
        win: Boolean,
    ): List<String>? {
        if (!win) return rtok.sys.Shlex.split(raw)
        val words = ArrayList<String>()
        val cur = StringBuilder()
        var quoted = false
        var open = false
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            open = open || !c.isWhitespace() || quoted
            when {
                c == '\\' -> {
                    var run = 1
                    while (i + 1 < raw.length && raw[i + 1] == '\\') {
                        run++
                        i++
                    }
                    val escapes = i + 1 < raw.length && raw[i + 1] == '"'
                    repeat(if (escapes) run / 2 else run) { cur.append('\\') }
                    if (escapes && run % 2 == 1) {
                        cur.append('"')
                        i++
                    }
                }

                c == '"' -> {
                    quoted = !quoted
                }

                c.isWhitespace() && !quoted -> {
                    if (open) {
                        words += cur.toString()
                        cur.clear()
                        open = false
                    }
                }

                else -> {
                    cur.append(c)
                }
            }
            i++
        }
        if (open) words += cur.toString()
        return if (quoted) null else words
    }

    /** [raw] without its `"..."` parts: a `(` in `C:\Program Files (x86)` is not shell syntax. */
    fun outsideQuotes(raw: String): String {
        var quoted = false
        return raw.filter { c ->
            quoted = quoted xor (c == '"')
            !quoted && c != '"'
        }
    }

    /** `%NAME%` as `${NAME}`, so the one expansion serves `cmd` variables too. */
    fun percentVars(word: String): String {
        val out = StringBuilder()
        var rest = word
        while (true) {
            val i = rest.indexOf('%')
            if (i < 0) break
            out.append(rest, 0, i)
            val tail = rest.substring(i + 1)
            val close = tail.indexOf('%')
            val name = if (close >= 0) tail.substring(0, close) else ""
            if (close >= 0 && name.isNotEmpty() && name.all(::isNameChar)) {
                out.append("\${").append(name).append('}')
                rest = tail.substring(close + 1)
            } else {
                out.append('%')
                rest = tail
            }
        }
        return out.append(rest).toString()
    }

    /** `C:\x`, `C:/x` or `\\server\share`: absolute by Windows rules whatever OS runs the check. */
    fun isAbsWindows(t: String) = t.startsWith("\\\\") || (t.length > 2 && t[0].isAsciiLetter() && t[1] == ':' && (t[2] == '\\' || t[2] == '/'))

    private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'

    private fun baseName(program: String): String {
        val name = program.substringAfterLast('/').substringAfterLast('\\')
        return name.removeSuffix(".exe").lowercase()
    }

    /** A command's target: the path or program to look up, and whether the shell execs it directly. */
    sealed class Target {
        data class Found(
            val target: String,
            val direct: Boolean,
        ) : Target()

        data class Not(
            val verdict: Verdict,
        ) : Target()
    }

    private fun unverified(why: String) = Target.Not(Verdict.Unverified(why))

    /**
     * What a command runs: the path or program to look up, and whether the shell would exec it directly (so its own
     * exec bit matters) rather than an interpreter reading it.
     */
    @Suppress("ReturnCount", "CyclomaticComplexMethod") // one branch per Rust arm
    fun target(
        words: List<String>,
        win: Boolean,
    ): Target {
        val program = words[0]
        val base = baseName(program)
        val args = words.drop(1)

        fun firstArg(a: List<String>) = a.firstOrNull { !it.startsWith('-') }

        fun flag(names: Set<String>) = args.indexOfFirst { it.lowercase() in names }.takeIf { it >= 0 }
        // `cmd /c <command>` runs the command itself; PowerShell runs the script after `-File`.
        if (win && base == "cmd") {
            val i = flag(setOf("/c", "/k"))
            return if (i != null && i + 1 < args.size) target(args.drop(i + 1), win) else unverified("no command after /c")
        }
        if (win && (base == "powershell" || base == "pwsh")) {
            val script = flag(setOf("-file", "-f"))?.let { args.getOrNull(it + 1) }
            return if (script != null) Target.Found(script, false) else unverified("an inline PowerShell command")
        }
        if (base in INTERPRETERS) {
            if (args.any { it in INLINE_FLAGS }) return unverified("an inline script, not a file")
            val scriptArgs = if (base == "deno" || base == "bun") args.filter { it != "run" } else args
            return firstArg(scriptArgs)?.let { Target.Found(it, false) } ?: unverified(NO_SCRIPT)
        }

        // Launchers that hand the rest of the line to another program.
        fun restAfter(word: String) = args.dropWhile { it != word }.drop(1)
        val first = firstArg(args)
        return when {
            base == "npx" && first == "tsx" -> {
                target(listOf("tsx") + restAfter("tsx"), win)
            }

            base == "uv" && first == "run" -> {
                val rest = restAfter("run")
                if (rest.isEmpty()) return unverified(NO_SCRIPT)
                when (val t = target(rest, win)) {
                    is Target.Found -> t.copy(direct = false)
                    is Target.Not -> t
                }
            }

            else -> {
                Target.Found(program, true)
            }
        }
    }

    /**
     * [command] as the shell would see it: words split, variables and `~` expanded, a `./` path made absolute where the
     * host documents its base. What cannot be expanded or split is kept as written with its spacing collapsed, so two
     * copies compare equal only when they really match.
     */
    fun normalize(
        command: String,
        p: Probes,
        scope: Scope,
    ): String {
        val raw = command.trim()
        val win = p.env.windows()
        val words = splitWords(raw, win)
        if (shellSyntax(syntaxText(raw, win)) != null || words == null) return raw.split(WS).filter { it.isNotEmpty() }.joinToString(" ")
        return words.joinToString(" ") { w ->
            val x = (expand(w, p, scope) as? Expanded.Word)?.text ?: w
            val rest = x.removePrefixOrNull("./") ?: if (win) x.removePrefixOrNull(".\\") else null
            if (rest != null && scope.relativeOk) joinProject(scope.project, rest, win) else x
        }
    }

    private fun String.removePrefixOrNull(prefix: String) = if (startsWith(prefix)) substring(prefix.length) else null

    /** The text [shellSyntax] judges: on Windows without the quoted parts. */
    private fun syntaxText(
        raw: String,
        win: Boolean,
    ) = if (win) outsideQuotes(raw) else raw

    /** [rest] under the project directory, with the separator of the rules in force. */
    private fun joinProject(
        project: String,
        rest: String,
        win: Boolean,
    ) = if (win) "$project\\${rest.replace('/', '\\')}" else rtok.sys.join(project, rest)

    private fun pathLike(t: String) = t.contains('/') || t.contains('\\') || t.startsWith('~') || t.startsWith('.')

    private fun isAssignment(w: String): Boolean {
        val eq = w.indexOf('=')
        if (eq <= 0) return false
        return w.substring(0, eq).all(::isNameChar)
    }

    @Suppress("ReturnCount", "CyclomaticComplexMethod") // one branch per Rust arm
    fun classify(
        command: String,
        p: Probes,
        scope: Scope,
    ): Verdict {
        val raw = command.trim()
        if (raw.isEmpty()) return Verdict.Unverified("empty command")
        // POSIX word splitting reads `\` as an escape, so Windows commands get their own rules.
        val win = p.env.windows()
        shellSyntax(syntaxText(raw, win))?.let { return Verdict.Unverified("$it: cannot be checked without running it") }
        val words = splitWords(raw, win) ?: return Verdict.Unverified("unbalanced quotes")
        val skip = words.takeWhile { !win && isAssignment(it) }.size
        if (skip == words.size) return Verdict.Unverified("only variable assignments")
        val expanded = ArrayList<String>()
        for (w in words.drop(skip)) {
            when (val x = expand(w, p, scope)) {
                is Expanded.Word -> {
                    expanded += x.text
                }

                is Expanded.Unset -> {
                    return Verdict.Unverified(
                        if (x.name == PLUGIN_ROOT) "`$PLUGIN_ROOT` is only known inside a plugin's own hooks.json" else "`${x.name}` is not set here",
                    )
                }
            }
        }
        val (target, direct) =
            when (val t = target(expanded, win)) {
                is Target.Found -> t.target to t.direct
                is Target.Not -> return t.verdict
            }
        if (win) return classifyWindows(target, p, scope)
        if (!pathLike(target) && direct) {
            return if (target in BUILTINS || p.which.find(target) != null) Verdict.Ok else Verdict.Broken("`$target` not on PATH")
        }
        val path =
            when {
                target.startsWith('/') -> target
                scope.relativeOk -> rtok.sys.join(scope.project, target.removePrefix("./"))
                else -> return Verdict.Unverified(NO_BASE)
            }
        unmountedVolume(path, p.fs)?.let { return Verdict.Broken("path is on $it, which is not mounted") }
        return verdict(path, p.fs.kind(path), direct)
    }

    /** What [kind] of the file at [path] means for a hook that runs it; [execBit]: the shell execs it directly. */
    private fun verdict(
        path: String,
        kind: PathKind,
        execBit: Boolean,
    ): Verdict =
        when (kind) {
            PathKind.Missing -> {
                Verdict.Broken("file not found: $path")
            }

            is PathKind.DanglingSymlink -> {
                Verdict.Broken("dangling symlink to ${kind.target}")
            }

            PathKind.Dir -> {
                Verdict.Broken("$path is a directory, not a script")
            }

            is PathKind.File -> {
                if (!kind.executable && execBit) Verdict.Suspect("$path is not executable: run `chmod +x`") else Verdict.Ok
            }
        }

    /**
     * Where a hook's target is on Windows (T331.9): a path as written, a bare name on `PATH`; each tried as is and with
     * every `PATHEXT` extension, as `cmd` does. The first file wins.
     */
    @Suppress("ReturnCount", "CyclomaticComplexMethod") // one branch per Rust arm
    private fun classifyWindows(
        target: String,
        p: Probes,
        scope: Scope,
    ): Verdict {
        val lower = target.lowercase()
        val bare = !pathLike(target)
        if (bare && (lower in BUILTINS || lower in CMD_BUILTINS)) return Verdict.Ok
        val exts = (p.env.variable("PATHEXT") ?: ".COM;.EXE;.BAT;.CMD").split(';').filter { it.isNotEmpty() }
        val hasExt = exts.any { lower.endsWith(it.lowercase()) }
        val bases =
            when {
                bare -> {
                    p.env
                        .variable("PATH")
                        .orEmpty()
                        .split(';')
                        .filter { it.isNotEmpty() }
                        .map { "${it.trimEnd('\\')}\\$target" }
                }

                isAbsWindows(target) -> {
                    listOf(target)
                }

                scope.relativeOk -> {
                    val rel = target.removePrefixOrNull("./") ?: target.removePrefixOrNull(".\\") ?: target
                    listOf(joinProject(scope.project, rel, true))
                }

                else -> {
                    return Verdict.Unverified(NO_BASE)
                }
            }
        for (base in bases) {
            val tries = listOf("") + if (hasExt) emptyList() else exts
            for (ext in tries) {
                val path = "$base$ext"
                val kind = p.fs.kind(path)
                if (kind is PathKind.File) return verdict(path, kind, false)
            }
        }
        val first = bases.firstOrNull()
        return if (first != null && !bare) verdict(first, p.fs.kind(first), false) else Verdict.Broken("`$target` not on PATH")
    }

    /**
     * `/Volumes/X` when [path] sits on a macOS volume that is not mounted. `/Volumes` exists only on macOS; elsewhere
     * such a path is an ordinary path and the missing-file rule covers it.
     */
    @OptIn(ExperimentalNativeApi::class)
    @Suppress("ReturnCount")
    private fun unmountedVolume(
        path: String,
        fs: ProbeFs,
    ): String? {
        if (Platform.osFamily != OsFamily.MACOSX) return null
        val parts = rtok.sys.components(path)
        if (parts.size < VOLUME_PARTS || parts[0] != "/" || parts[1] != "Volumes") return null
        val mount = "/Volumes/${parts[2]}"
        return if (fs.kind(mount) == PathKind.Missing) mount else null
    }
}
