// Port of src/plugins/cmd/formatters.rs — route a command's output to its formatter or rule:
// wrapper peeling (`mise exec`, `just`, env assigns, `uv run`, …), mixed-chain detection, the
// per-family formatters, and the rule-engine fallback.
package rtok.plugins.cmd

import rtok.cmdStem
import rtok.sys.rustLines

/** A compressed body and how it was made: `raw`, `formatter`, `rule`, `skill` or `unmatched`. */
data class Compressed(
    val text: String,
    val kind: String,
)

object Formatters {
    fun compress(
        settings: Settings,
        argv: List<String>,
        output: String,
        exit: Int,
        archiveId: String,
    ): Compressed {
        // T677: mise's install chatter goes before the inner command's formatter or rule looks at the text.
        // T678: a polling loop collapses before the route is chosen (a loop over two programs is a mixed chain).
        val pre =
            when (bin(familyArgv(argv))) {
                "mise" -> Mise.tidy(output)
                "for", "while", "until" -> Loops.collapse(output)
                else -> null
            }
        if (pre != null) {
            val got = compressText(settings, argv, pre, exit, archiveId)
            return if (got.kind == "raw" || got.kind == "unmatched") got.copy(kind = "formatter") else got
        }
        return compressText(settings, argv, output, exit, archiveId)
    }

    private fun bounded(
        argv: List<String>,
        output: String,
    ) = output.byteLen <= Bounded.MAX_BYTES && Bounded.isBounded(argv.joinToString(" "))

    private fun compressText(
        settings: Settings,
        argv: List<String>,
        output: String,
        exit: Int,
        archiveId: String,
    ): Compressed {
        // T176: `sed -n 1,620p`, `| tail -300`, `grep -A3` already printed what was asked for.
        if (bounded(argv, output)) return Compressed(output, "raw")
        // T177: a chain of 2+ distinct programs is not one family's output; it routes to `[script]`.
        val matched = resolve(settings, argv, output)
        val stem = if (matched.multi) "script" else bin(matched.argv)
        // The outer line is `mise`, which is not a bounded command; the peeled `sed -n` is.
        if (!matched.multi && bounded(matched.argv, output)) return Compressed(output, "raw")
        val rule = settings.pick(stem)
        // T65.2: JSON bodies skip table formatters so `kubectl -o json` / `gh --json` reach the compact pass.
        Rules.parseJsonBody(output)?.let { json ->
            val s = Rules.applyParsed(settings, output, json, exit, rule, archiveId)
            return Compressed(s, if (s.byteLen < output.byteLen) "rule" else "unmatched")
        }
        if (!matched.multi) format(matched.argv, output)?.let { return Compressed(it, "formatter") }
        val s = Rules.apply(settings, output, exit, rule, archiveId)
        return Compressed(
            s,
            when {
                stem == "skill" -> "skill"
                s.byteLen < output.byteLen -> "rule"
                else -> "unmatched"
            },
        )
    }

    /** One quoted Bash string splits on whitespace; a real argv stays as it is. */
    fun familyArgv(argv: List<String>): List<String> = if (argv.size == 1) argv[0].split(' ', '\t', '\n', '\r').filter { it.isNotEmpty() } else argv

    /** The family name a command's measurements are filed under. */
    fun family(argv: List<String>): String {
        // A pipe stays the first command; a wrapper around two different programs is `[script]`.
        if (argv.size == 1 && Bounded.mixedChain(argv[0])) {
            val raw = familyArgv(argv)
            val rawBin = bin(raw)
            val peeled = bin(visibleArgv(raw))
            if (peeled != rawBin && wrapperStem(rawBin)) return "script"
            return named(raw)
        }
        // `commandArgv` sees through `cd && mise exec -- cargo`, which `visibleArgv` on the whole line does not.
        return named(commandArgv(argv))
    }

    fun familyOf(
        settings: Settings,
        argv: List<String>,
        output: String,
    ): String {
        val base = family(argv)
        if (base != "mise" && base != "just") return base
        val r = echoed(settings, base, output) ?: return base
        return if (r.multi) "script" else named(r.argv)
    }

    private fun named(argv: List<String>) = bin(argv).ifEmpty { "other" }

    internal val FORMATTER_STEMS =
        setOf(
            "cargo",
            "git",
            "gh",
            "pytest",
            "python",
            "python3",
            "py",
            "jest",
            "vitest",
            "tree",
            "go",
            "docker",
            "podman",
            "kubectl",
            "ps",
            "df",
            "sqlite3",
            "wc",
            "mise",
            "for",
            "while",
            "until",
        )

    fun filterKind(
        settings: Settings,
        stem: String,
    ): String =
        when {
            stem in FORMATTER_STEMS -> "formatter"
            settings.pick(stem).matchCmd == stem -> "rule"
            else -> "default"
        }

    internal fun bin(argv: List<String>) = argv.firstOrNull()?.let(::cmdStem) ?: ""

    internal fun sub(argv: List<String>) = argv.getOrNull(1) ?: ""

    /** [argv] to format by; [multi]: two or more distinct programs, so `[script]`. */
    private data class Resolved(
        val argv: List<String>,
        val multi: Boolean,
    )

    private val SCRIPT = Resolved(listOf("script"), true)

    private fun resolve(
        settings: Settings,
        argv: List<String>,
        output: String,
    ): Resolved {
        if (argv.size == 1 && Bounded.mixedChain(argv[0])) return SCRIPT
        val vis = commandArgv(argv)
        val stem = bin(vis)
        if (stem == "mise" || stem == "just") echoed(settings, stem, output)?.let { return it }
        return Resolved(vis, false)
    }

    // ---- wrapper peeling ----

    private val PEELS: List<(List<String>) -> List<String>?> = listOf(::peelMiseExec, ::peelJustCommand, ::peelLeadingEnv, ::peelToolWrapper)

    /** [argv] with up to four wrapper layers peeled (`mise exec --`, `just -c`, `FOO=1`, `uv run`, …). */
    internal fun visibleArgv(argv: List<String>): List<String> {
        var cur = argv
        repeat(4) {
            val inner = PEELS.firstNotNullOfOrNull { it(cur) } ?: return cur
            if (inner == cur) return cur
            cur = inner
        }
        return cur
    }

    private fun commandArgv(argv: List<String>): List<String> {
        val line = if (argv.size == 1) argv[0] else argv.joinToString(" ")
        val stages = Bounded.stages(line).filter { it.isNotEmpty() && !silentStage(it) }
        if (stages.any(::isWrapperStage)) {
            val peeled = stages.map(::visibleArgv)
            val first = peeled.firstOrNull()
            if (first != null) {
                val stem = bin(first)
                if (stem.isNotEmpty() && peeled.all { bin(it) == stem }) {
                    return if (peeled.all { sub(it) == sub(first) }) first else listOf(stem)
                }
            }
        }
        return visibleArgv(familyArgv(argv))
    }

    private fun silentStage(stage: List<String>) = stage.firstOrNull().let { it == "cd" || it == "export" }

    private fun isWrapperStage(stage: List<String>) = PEELS.any { it(stage) != null }

    private val WRAPPER_STEMS = setOf("mise", "just", "uv", "uvx", "npx", "pnpm", "yarn", "poetry", "pipenv", "hatch", "python", "python3", "py", "cmd")

    private fun wrapperStem(stem: String) = stem in WRAPPER_STEMS || isEnvAssign(stem)

    private fun words(s: String) = s.split(' ', '\t', '\n', '\r').filter { it.isNotEmpty() }

    private fun peelMiseExec(argv: List<String>): List<String>? {
        if (cmdStem(argv.firstOrNull() ?: return null) != "mise") return null
        var i = skipMiseFlags(argv, 1) ?: return null
        if (i >= argv.size || (argv[i] != "exec" && argv[i] != "x")) return null
        i++
        while (i < argv.size) {
            val tok = argv[i]
            if (tok == "--") return argv.subList(i + 1, argv.size).takeIf { it.isNotEmpty() }
            if (tok == "-c" || tok == "--command") return words(argv.getOrNull(i + 1) ?: return null).takeIf { it.isNotEmpty() }
            val n = miseFlagWidth(tok)
            if (n != null) {
                if (i + n > argv.size) return null
                i += n
                continue
            }
            if (tok.startsWith('-')) return null
            i++
        }
        return null
    }

    private fun peelLeadingEnv(argv: List<String>): List<String>? {
        var i = 0
        while (i < argv.size && isEnvAssign(argv[i])) i++
        return if (i > 0 && i < argv.size) argv.subList(i, argv.size) else null
    }

    private fun isEnvAssign(tok: String): Boolean {
        val eq = tok.indexOf('=').takeIf { it >= 0 } ?: return false
        val key = tok.substring(0, eq)
        val value = tok.substring(eq + 1)
        // A quote in the value is an unclosed `FOO='x cd y`; peeling it would name `cd` instead.
        return value.isNotEmpty() && '\'' !in value && '"' !in value &&
            key.firstOrNull()?.let { it in 'a'..'z' || it in 'A'..'Z' || it == '_' } == true &&
            key.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' }
    }

    private fun peelToolWrapper(argv: List<String>): List<String>? {
        val a1 = argv.getOrNull(1)
        val restAt =
            when (cmdStem(argv.firstOrNull() ?: return null)) {
                "uv" -> if (a1 == "run") 2 else return null
                "uvx", "npx" -> 1
                "pnpm" -> if (a1 == "exec" || a1 == "dlx") 2 else return null
                "yarn" -> if (a1 == "dlx") 2 else return null
                "poetry", "pipenv", "hatch" -> if (a1 == "run") 2 else return null
                "python", "python3", "py" -> if (a1 == "-m") 2 else return null
                "cmd" -> if (a1?.equals("/c", ignoreCase = true) == true) 2 else return null
                else -> return null
            }
        return commandAfter(argv, restAt)
    }

    private fun commandAfter(
        argv: List<String>,
        at: Int,
    ): List<String>? {
        if (at > argv.size) return null
        var rest = argv.subList(at, argv.size)
        if (rest.firstOrNull() == "--") rest = rest.subList(1, rest.size)
        if (rest.isEmpty() || rest[0].startsWith('-')) return null
        if (rest.size == 1 && rest[0].any { it.isWhitespace() }) return words(rest[0]).takeIf { it.isNotEmpty() }
        return rest
    }

    private fun peelJustCommand(argv: List<String>): List<String>? {
        if (cmdStem(argv.firstOrNull() ?: return null) != "just") return null
        var i = 1
        while (i < argv.size) {
            val tok = argv[i]
            if (tok == "-c" || tok == "--command") return argv.subList(i + 1, argv.size).takeIf { it.isNotEmpty() }
            if (!tok.startsWith('-')) return null
            val extra = justFlagValues(tok)
            if (i + extra >= argv.size) return null
            i += 1 + extra
        }
        return null
    }

    private val JUST_VALUE_FLAGS =
        setOf(
            "-f",
            "--justfile",
            "-d",
            "--working-directory",
            "--color",
            "--shell",
            "--shell-arg",
            "--command-color",
            "--dotenv-filename",
            "--dotenv-command",
            "--chooser",
            "--ceiling",
            "--justfile-name",
        )

    private fun justFlagValues(tok: String) =
        when {
            '=' in tok -> 0
            tok == "--set" -> 2
            tok in JUST_VALUE_FLAGS -> 1
            else -> 0
        }

    private fun skipMiseFlags(
        argv: List<String>,
        from: Int,
    ): Int? {
        var i = from
        while (i < argv.size) {
            val tok = argv[i]
            if (tok in setOf("exec", "x", "run", "r", "--")) break
            val n = miseFlagWidth(tok)
            if (n != null) {
                if (i + n > argv.size) return null
                i += n
                continue
            }
            if (tok.startsWith('-')) return null
            break
        }
        return i
    }

    private val MISE_VALUE = setOf("-C", "--cd", "-E", "--env", "-j", "--jobs", "--allow-env", "--allow-net", "--allow-read", "--allow-write")
    private val MISE_BOOL =
        setOf(
            "-q",
            "-v",
            "-y",
            "--quiet",
            "--verbose",
            "--yes",
            "--locked",
            "--silent",
            "--raw",
            "--no-deps",
            "--fresh-env",
            "--deny-all",
            "--deny-env",
            "--deny-net",
            "--deny-read",
            "--deny-write",
        )

    private fun miseFlagWidth(tok: String): Int? {
        val eq = tok.indexOf('=')
        if (eq >= 0 && tok.startsWith("--")) return miseFlagWidth(tok.substring(0, eq))?.let { 1 }
        return when {
            tok in MISE_VALUE -> 2
            tok in MISE_BOOL || isVerboseShort(tok) -> 1
            else -> null
        }
    }

    private fun isVerboseShort(tok: String) = tok.length > 2 && tok.all { it == '-' || it == 'v' } && tok.endsWith('v')

    // ---- echoed task commands (`[task] $ cmd`, `just` recipe echo) ----

    private fun echoed(
        settings: Settings,
        wrapper: String,
        output: String,
    ): Resolved? {
        var first: List<String>? = null
        var stem = ""
        var firstSub = ""
        var subDiffers = false
        for (line in output.rustLines()) {
            val cmd = echoCommand(settings, wrapper, line) ?: continue
            if (Bounded.mixedChain(cmd)) return SCRIPT
            val vis = visibleArgv(familyArgv(listOf(cmd)))
            val got = bin(vis)
            if (got.isEmpty()) continue
            if (stem.isEmpty()) {
                stem = got
                firstSub = sub(vis)
                first = vis
                continue
            }
            if (got != stem) return SCRIPT
            if (sub(vis) != firstSub) subDiffers = true
        }
        val argv = first ?: return null
        return Resolved(if (subDiffers) listOf(stem) else argv, false)
    }

    private fun echoCommand(
        settings: Settings,
        wrapper: String,
        line: String,
    ): String? {
        val stripped = stripAnsi(line)
        if (stripped.startsWith(' ') || stripped.startsWith('\t')) return null
        val t = stripped.trim()
        // A long line that starts with a known stem is tool output, not the echoed command.
        if (t.isEmpty() || t.byteLen > 400) return null
        taskDollar(t)?.let { return it }
        if (wrapper != "just") return null
        val words = words(t)
        val stem = cmdStem(words.firstOrNull() ?: return null)
        if (stem == "mise" || stem == "just") return t
        if (filterKind(settings, stem) != "default" && looksLikeInvocation(words)) return t
        return null
    }

    private fun taskDollar(line: String): String? {
        val rest = line.removePrefixOrNull("[") ?: return null
        val at = rest.indexOf("] $ ").takeIf { it >= 0 } ?: return null
        val name = rest.substring(0, at)
        val cmd = rest.substring(at + 4)
        return if (name.isEmpty() || cmd.isEmpty()) null else cmd
    }

    private fun looksLikeInvocation(words: List<String>): Boolean {
        val s = words.getOrNull(1) ?: return true
        if (s.contains("ERR") || s.equals("error", ignoreCase = true) || s.equals("warning", ignoreCase = true)) return false
        return ':' !in s && s.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' }
    }

    // ---- per-family formatters ----

    private fun firstLines(
        output: String,
        n: Int,
    ) = output.rustLines().take(n).joinToString("\n")

    internal fun format(
        argv: List<String>,
        output: String,
    ): String? {
        val b = bin(argv)
        val s = sub(argv)
        return when {
            b == "cargo" && s == "test" -> Compact.cargoTest(output) ?: keep(output, listOf("FAILED", "test result:", "error[", "panicked"))

            b == "cargo" && (s == "build" || s == "clippy") -> Compact.cargoBuild(output) ?: keep(output, listOf("error[", "error:", "-->", "Finished", "warning:"))

            b == "git" && s == "status" -> Compact.gitStatus(output) ?: gitStatus(output)

            b == "git" && s == "diff" -> Diffs.tiered(output) ?: Compact.gitDiff(gitDiff(output))

            // `git show <rev>:<file>` prints a file, not a diff: only a diff stream is touched.
            b == "git" && s == "show" -> Diffs.tiered(output)

            b == "ls" && Compact.lsLong(argv) -> Compact.lsL(output)

            b == "cat" -> CatFile.outlineOf(argv, output)

            b == "grep" || b == "rg" || (b == "git" && s == "grep") -> Grep.group(argv, output)

            b == "git" && s == "log" -> gitLog(output) ?: firstLines(output, 20)

            b == "gh" -> Gh.format(argv, output)

            b == "pytest" -> Python.pytest(output) ?: keep(output, listOf("FAILED", "ERROR", "passed", "failed", "error"))

            b == "python" || b == "python3" || b == "py" -> Python.script(output)

            b == "jest" || b == "vitest" -> keep(output, listOf("FAIL", "PASS", "Tests:", "● "))

            b == "go" && s == "test" -> keep(output, listOf("FAIL", "PASS", "ok  ", "--- FAIL"))

            b == "wc" -> output.rustLines().joinToString("\n") { Rules.collapseColumns(it.trim()) }

            b == "for" || b == "while" || b == "until" -> Loops.collapse(output)

            b == "tree" -> firstLines(output, 40)

            // `podman ps` prints the same table (T905).
            (b == "docker" || b == "podman") && s == "ps" -> Tables.dockerPs(output)

            b == "kubectl" && s == "get" -> Tables.kubectlGet(output)

            b == "ps" && s == "aux" -> Tables.psAux(output)

            b == "df" -> Tables.df(output)

            b == "sqlite3" -> Tables.sqlite3(output)

            b == "mise" -> Mise.tidy(output)

            else -> null
        }
    }

    private const val GIT_LOG_MAX_COMMITS = 100

    private class Commit(
        val sha: String,
        var author: String = "",
        var date: String = "",
        var year: String = "",
        var subject: String = "",
        val stat: MutableList<String> = ArrayList(),
    )

    /** `N files changed, A insertions(+), D deletions(-)` → (A, D). */
    private fun statTotals(line: String): Pair<Int, Int>? {
        if (line.contains(" | ") || !line.contains(" changed")) return null

        fun count(word: String) = line.split(',').firstOrNull { it.contains(word) }?.let { words(it).firstOrNull()?.toIntOrNull() } ?: 0
        return count("insertion") to count("deletion")
    }

    internal fun gitLog(output: String): String? {
        val commits = ArrayList<Commit>()
        for (line in output.rustLines()) {
            val rest = line.removePrefixOrNull("commit ")
            if (rest != null) {
                val sha = words(rest).firstOrNull() ?: return null
                if (sha.length < 7 || !sha.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
                commits += Commit(sha.substring(0, 7))
                continue
            }
            val c = commits.lastOrNull() ?: return null
            when {
                line.startsWith("Author:") -> {
                    c.author = line.substring(7).trim().substringBefore(" <")
                }

                // `Wed Oct 14 23:25:53 2026 +0300` → `Oct 14`, year kept aside.
                line.startsWith("Date:") -> {
                    words(line.substring(5)).takeIf { it.size >= 5 }?.let {
                        c.date = "${it[1]} ${it[2]}"
                        c.year = it[4]
                    }
                }

                line.startsWith("Merge:") || line.isBlank() -> {}

                line.startsWith("    ") -> {
                    if (c.subject.isEmpty()) c.subject = line.substring(4).trim()
                }

                line.startsWith(' ') && (line.contains(" | ") || line.contains(" changed")) -> {
                    c.stat += line.trim()
                }

                line.startsWith("diff --git") || line.startsWith("@@") -> {
                    return null
                }
            }
        }
        if (commits.isEmpty() || commits.any { it.date.isEmpty() }) return null
        // The most frequent author goes in the header once; only the others are named per line.
        val counts = LinkedHashMap<String, Int>()
        for (c in commits) counts[c.author] = (counts[c.author] ?: 0) + 1
        var mainAuthor = ""
        var best = 0
        for ((a, n) in counts) {
            if (n > best) {
                mainAuthor = a
                best = n
            }
        }
        val oneYear = commits.all { it.year == commits[0].year }
        val head = ArrayList<String>()
        if (mainAuthor.isNotEmpty()) head += if (counts.size == 1) mainAuthor else "$mainAuthor unless named"
        if (oneYear) head += commits[0].year
        // T682: the count and the `--stat` totals lead; totals only when `--stat` printed them.
        var added = 0
        var removed = 0
        var hasTotals = false
        for (l in commits.flatMap { it.stat }) {
            statTotals(l)?.let {
                added += it.first
                removed += it.second
                hasTotals = true
            }
        }
        val n = commits.size
        var countsLine = "[$n commit${if (n == 1) "" else "s"}"
        if (hasTotals) countsLine += ", +$added/-$removed"
        countsLine += "]"
        if (head.isNotEmpty()) countsLine += " " + head.joinToString(" · ")
        val out = arrayListOf(countsLine)
        for (c in commits.take(GIT_LOG_MAX_COMMITS)) {
            val date = if (oneYear) c.date else "${c.date} ${c.year}"
            out += "${c.sha} $date ${c.subject}" + if (c.author != mainAuthor) " — ${c.author}" else ""
            out += c.stat.map { "  $it" }
        }
        if (commits.size > GIT_LOG_MAX_COMMITS) out += "… +${commits.size - GIT_LOG_MAX_COMMITS} more"
        return out.joinToString("\n")
    }

    private fun gitDiff(output: String) = output.rustLines().filter { !it.startsWith("index ") }.joinToString("\n")

    private val STATUS_VERBS = listOf("modified:", "new file:", "deleted:", "renamed:", "copied:", "both modified:", "both added:", "both deleted:", "added by us:", "unmerged:")
    private val STATUS_LINES = listOf("On branch ", "Your branch ", "HEAD detached ", "nothing to commit", "no changes added to commit", "nothing added to commit")

    /** The fallback `git status` filter when [Compact.gitStatus] stands down. */
    private fun gitStatus(output: String): String {
        val out = ArrayList<String>()
        var untracked = false
        for (line in output.rustLines()) {
            val head = line.trimStart()
            when {
                head.startsWith("Untracked files:") -> {
                    untracked = true
                    out += head
                }

                STATUS_VERBS.any { head.startsWith(it) } || STATUS_LINES.any { head.startsWith(it) } -> {
                    untracked = false
                    out += head
                }

                isPorcelain(line) -> {
                    untracked = false
                    out += line
                }

                // An untracked path, indented under its header in the long format.
                untracked && (line.startsWith('\t') || line.startsWith(' ')) && !head.startsWith("(use ") -> {
                    out += head
                }
            }
        }
        return if (out.isEmpty()) firstLines(output, 15) else out.joinToString("\n")
    }

    private fun isPorcelain(line: String): Boolean {
        fun col(c: Char) = c in " MADRCU?!"
        return line.byteLen > 3 && col(line[0]) && col(line[1]) && line[2] == ' '
    }

    /** Lines holding any of [needles], else the last 8 lines. */
    private fun keep(
        output: String,
        needles: List<String>,
    ): String {
        val all = output.rustLines()
        val lines = all.filter { l -> needles.any { l.contains(it) } }
        return (lines.ifEmpty { all.takeLast(8) }).joinToString("\n")
    }
}
