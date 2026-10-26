// Port of src/plugins/cmd/compact.rs — cargo build/test, git status/diff and `ls -l` compaction.
package rtok.plugins.cmd

import rtok.sys.rustLines

object Compact {
    private val CARGO_PROGRESS =
        listOf(
            "Compiling ",
            "Checking ",
            "Downloading ",
            "Downloaded ",
            "Fresh ",
            "Blocking ",
            "Locking ",
            "Updating ",
            "Adding ",
            "Running ",
            "Doc-tests ",
        )

    private fun isProgress(line: String) = line.trimStart().let { t -> CARGO_PROGRESS.any { t.startsWith(it) } }

    private fun shortFinished(line: String): String? {
        val t = line.trimStart().takeIf { it.startsWith("Finished ") }?.substring(9) ?: return null
        val profile = (t.split('`').getOrNull(1) ?: "").trim()
        return if (profile.isEmpty()) "Finished" else "Finished $profile"
    }

    private fun isGeneratedCount(line: String) = line.startsWith("warning: `") && line.contains(" generated ") && line.contains("warning")

    private class Warn(
        val head: String,
        val locs: MutableList<String> = ArrayList(),
        var code: String? = null,
    )

    /** A kept line, or the index of a warning block. */
    private sealed interface Piece {
        class Line(
            val text: String,
        ) : Piece

        class W(
            val i: Int,
        ) : Piece
    }

    private fun lintCode(line: String): String? {
        line.split("#[warn(").getOrNull(1)?.let { return it.split(')')[0] }
        val r =
            line
                .split("rust-clippy/")
                .getOrNull(1)
                ?.split("index.html#")
                ?.getOrNull(1) ?: return null
        return "clippy::" + (r.split(' ', '\t').firstOrNull { it.isNotEmpty() } ?: return null)
    }

    /** The headline without its backtick-quoted parts: warnings of one lint share it. */
    private fun headShape(head: String) = head.split('`').filterIndexed { i, _ -> i % 2 == 0 }.joinToString("")

    fun cargoBuild(output: String): String? {
        val pieces = ArrayList<Piece>()
        val warns = ArrayList<Warn>()
        var open = false
        for (line in output.rustLines()) {
            if (isProgress(line) || isGeneratedCount(line)) continue
            val f = shortFinished(line)
            when {
                f != null -> {
                    open = false
                    pieces += Piece.Line(f)
                }

                line.startsWith("warning:") -> {
                    open = true
                    warns += Warn(line)
                    pieces += Piece.W(warns.size - 1)
                }

                open && line.trimStart().startsWith("-->") -> {
                    warns.last().locs += line
                }

                open && lintCode(line) != null -> {
                    warns.last().let { if (it.code == null) it.code = lintCode(line) }
                }

                listOf("error[", "error:", "-->", "warning:").any { line.contains(it) } -> {
                    open = false
                    pieces += Piece.Line(line)
                }
            }
        }
        if (pieces.isEmpty()) return null
        val byShape = HashMap<String, String>()
        for (w in warns) w.code?.let { byShape.getOrPut(headShape(w.head)) { it } }

        fun lint(w: Warn) = w.code ?: byShape[headShape(w.head)]

        fun key(w: Warn) = lint(w) ?: headShape(w.head)
        val groups = HashMap<String, MutableList<Int>>()
        for ((i, w) in warns.withIndex()) groups.getOrPut(key(w)) { ArrayList() } += i
        val kept = ArrayList<String>()
        for (piece in pieces) {
            if (piece is Piece.Line) {
                kept += piece.text
                continue
            }
            val i = (piece as Piece.W).i
            val members = groups.getValue(key(warns[i]))
            if (members.size < 2) {
                kept += warns[i].head
                kept += warns[i].locs
            } else if (members[0] == i) {
                val locs =
                    members.flatMap { warns[it].locs }.mapNotNull { l ->
                        l
                            .trimStart()
                            .takeIf { it.startsWith("-->") }
                            ?.substring(3)
                            ?.trim()
                    }
                val head = warns[i].head.removePrefixRepeated("warning: ")
                val label = lint(warns[i])?.let { "$it ×${members.size}: $head" } ?: "$head ×${members.size}"
                kept += "$label (${locs.joinToString(", ")})"
            }
        }
        return kept.joinToString("\n")
    }

    private class Totals(
        var suites: Int = 0,
        var passed: ULong = 0u,
        var failed: ULong = 0u,
        var ignored: ULong = 0u,
        var filtered: ULong = 0u,
    ) {
        /** `test result: ok. 3 passed; 0 failed; 1 ignored; 0 measured; 2 filtered out; …` */
        fun add(line: String): Boolean {
            if (!line.startsWith("test result: ")) return false
            var seen = false
            for (part in line.substring(13).split(';')) {
                val words = part.split(' ', '\t', '\n').filter { it.isNotEmpty() }
                val (n, what) =
                    when {
                        words.size >= 3 && words.last() == "out" -> words[words.size - 3] to words[words.size - 2]
                        words.size >= 2 -> words[words.size - 2] to words.last()
                        else -> continue
                    }
                val v = n.toULongOrNull() ?: continue
                seen = true
                when (what) {
                    "passed" -> passed += v
                    "failed" -> failed += v
                    "ignored" -> ignored += v
                    "filtered" -> filtered += v
                }
            }
            if (seen) suites++
            return seen
        }

        fun line(): String {
            val parts = arrayListOf("$passed passed")
            if (failed > 0u) parts += "$failed failed"
            if (ignored > 0u) parts += "$ignored ignored"
            if (filtered > 0u) parts += "$filtered filtered out"
            return "cargo test: ${parts.joinToString(", ")} ($suites suite${if (suites == 1) "" else "s"})"
        }
    }

    fun cargoTest(output: String): String? {
        val totals = Totals()
        val failed = ArrayList<String>()
        val bodies = ArrayList<String>()
        var inFailures = false
        var inBody = false
        for (line in output.rustLines()) {
            if (totals.add(line)) {
                inFailures = false
                inBody = false
                continue
            }
            if (line.startsWith("test ") && line.endsWith(" ... FAILED") && line.length >= 16) {
                failed += line.substring(5, line.length - 11)
                continue
            }
            if (line == "failures:") {
                // The first `failures:` opens the bodies, the second lists names we have.
                inBody = !inFailures
                inFailures = true
                continue
            }
            if (inFailures && inBody) {
                if (line.startsWith("---- ") || line.isBlank() || line.startsWith("note: run with `RUST_BACKTRACE")) continue
                bodies += line
            }
        }
        if (totals.suites == 0) {
            val compile = output.rustLines().any { it.startsWith("error[") || it.startsWith("error:") }
            return if (compile) cargoBuild(output) else null
        }
        val out = ArrayList(bodies)
        if (failed.isNotEmpty()) out += "FAILED: ${failed.joinToString(", ")}"
        out += totals.line()
        return out.joinToString("\n")
    }

    private enum class Section { None, Staged, Unstaged, Untracked, Unmerged }

    private val VERBS = listOf("modified:" to 'M', "new file:" to 'A', "deleted:" to 'D', "renamed:" to 'R', "copied:" to 'C', "typechange:" to 'T')
    private val UNMERGED =
        listOf(
            "both modified:" to "UU",
            "both added:" to "AA",
            "both deleted:" to "DD",
            "added by us:" to "AU",
            "added by them:" to "UA",
            "deleted by us:" to "DU",
            "deleted by them:" to "UD",
        )
    private val STATE_LINES =
        listOf(
            "On branch ",
            "Your branch ",
            "HEAD detached ",
            "nothing to commit",
            "You have unmerged paths",
            "All conflicts fixed",
            "You are currently ",
            "interactive rebase in progress",
            "Last command",
            "Next command",
            "No commits yet",
            "and have ",
        )

    private sealed interface StatusLine {
        class Text(
            val text: String,
        ) : StatusLine

        class Entry(
            val code: String,
            val path: String,
        ) : StatusLine
    }

    /** Long-format `git status` as porcelain codes, paths grouped by directory. */
    fun gitStatus(output: String): String? {
        val out = ArrayList<StatusLine>()
        var section = Section.None
        var entries = 0
        for (line in output.rustLines()) {
            val head = line.trim()
            if (head.isEmpty() || (head.startsWith('(') && head.endsWith(')'))) continue
            val indented = line.startsWith('\t') || line.startsWith(' ')
            when {
                head.startsWith("Changes to be committed:") -> {
                    section = Section.Staged
                }

                head.startsWith("Changes not staged for commit:") -> {
                    section = Section.Unstaged
                }

                head.startsWith("Untracked files:") -> {
                    section = Section.Untracked
                }

                head.startsWith("Unmerged paths:") -> {
                    section = Section.Unmerged
                }

                head.startsWith("no changes added to commit") || head.startsWith("nothing added to commit but untracked") -> {}

                // restates the sections
                indented && section != Section.None -> {
                    val code =
                        when (section) {
                            Section.Untracked -> "??"
                            Section.Unmerged -> UNMERGED.firstOrNull { head.startsWith(it.first) }?.second
                            else -> VERBS.firstOrNull { head.startsWith(it.first) }?.second?.let { if (section == Section.Staged) "$it " else " $it" }
                        } ?: return null
                    val colon = head.indexOf(':')
                    val path = if (colon >= 0 && section != Section.Untracked) head.substring(colon + 1).trim() else head
                    out += StatusLine.Entry(code, path)
                    entries++
                }

                indented -> {
                    return null
                }

                STATE_LINES.any { head.startsWith(it) } -> {
                    out += StatusLine.Text(head)
                }
                // Any other top-level line (an echoed command, a wrapper banner) is dropped.
            }
        }
        if (out.isEmpty() || (entries == 0 && section != Section.None)) return null
        return renderStatus(out)
    }

    private fun renderStatus(lines: List<StatusLine>): String {
        val out = ArrayList<String>()
        var run = ArrayList<Pair<String, String>>()
        var branch = -1
        for (line in lines) {
            if (line is StatusLine.Entry) {
                run += line.code to line.path
                continue
            }
            val text = (line as StatusLine.Text).text
            out += groupEntries(run)
            run = ArrayList()
            val note = if (branch >= 0) upstreamNote(text, out[branch]) else null
            when {
                text.startsWith("On branch ") -> {
                    branch = out.size
                    out += text
                }

                note != null -> {
                    out[branch] = note
                }

                else -> {
                    out += text
                }
            }
        }
        out += groupEntries(run)
        return out.joinToString("\n")
    }

    private fun upstreamNote(
        text: String,
        branch: String,
    ): String? {
        fun remote(rest: String): Pair<String, String>? = rest.indexOf('\'').takeIf { it >= 0 }?.let { rest.substring(0, it) to rest.substring(it + 1) }

        fun count(after: String): String? = after.indexOf(" by ").takeIf { it >= 0 }?.let { after.substring(it + 4).split(' ').firstOrNull { w -> w.isNotEmpty() } }
        if (text.startsWith("Your branch is up to date with '")) return branch
        text.removePrefixOrNull("Your branch is ahead of '")?.let { rest ->
            val (r, after) = remote(rest) ?: return null
            return "$branch ahead ${count(after) ?: return null} ($r)"
        }
        text.removePrefixOrNull("Your branch is behind '")?.let { rest ->
            val (r, after) = remote(rest) ?: return null
            return "$branch behind ${count(after) ?: return null} ($r)"
        }
        text.removePrefixOrNull("Your branch and '")?.let { rest ->
            val (r, _) = remote(rest) ?: return null
            return "$branch diverged ($r)"
        }
        val counts = text.removePrefixOrNull("and have ") ?: return null
        val and = counts.indexOf(" and ").takeIf { it >= 0 } ?: return null
        val ahead = counts.substring(0, and)
        val behind = counts.substring(and + 5).split(' ').firstOrNull { it.isNotEmpty() } ?: return null
        return if (branch.contains(" diverged (")) branch.replaceFirst(" diverged", " diverged +$ahead -$behind") else null
    }

    private fun groupEntries(run: List<Pair<String, String>>): List<String> {
        val lines = ArrayList<String>()
        for (code in run.map { it.first }.distinct()) {
            val (loose, plain) = run.filter { it.first == code }.map { it.second }.partition { p -> p.any { it in " ,{}" } }
            val dirs = LinkedHashMap<String, MutableList<String>>()
            for (path in plain) {
                val cut = path.trimEnd('/').lastIndexOf('/') + 1
                dirs.getOrPut(path.substring(0, cut)) { ArrayList() } += path.substring(cut)
            }
            val items =
                dirs.map { (dir, leaves) ->
                    when {
                        leaves.size == 1 -> dir + leaves[0]
                        dir.isEmpty() -> leaves.joinToString(" ")
                        else -> "$dir{${leaves.joinToString(",")}}"
                    }
                }
            if (items.isNotEmpty()) lines += "$code ${items.joinToString(" ")}"
            lines += loose.map { "$code $it" }
        }
        return lines
    }

    /** `git diff` without `index` lines and the redundant `diff --git`/`--- a/` headers. */
    fun gitDiff(output: String): String {
        val lines = output.rustLines()
        val out = ArrayList<String>(lines.size)
        var i = 0
        while (i < lines.size) {
            val l = lines[i]
            if (l.startsWith("index ")) {
                i++
                continue
            }
            // Keep `diff --git` only when no `+++` header names the file (binary or mode-only change).
            if (l.startsWith("diff --git ") &&
                lines
                    .subList(i + 1, lines.size)
                    .asSequence()
                    .takeWhile { !it.startsWith("diff --git ") && !it.startsWith("@@") }
                    .any { it.startsWith("+++ ") }
            ) {
                i++
                continue
            }
            if (l.startsWith("--- a/") && lines.getOrNull(i + 1)?.removePrefixOrNull("+++ b/") == l.substring(6)) {
                i++
                continue
            }
            out += l
            i++
        }
        return out.joinToString("\n")
    }

    /** Rust `format!("{v:.1}")` of bytes in K/M/G/T, rounded half-to-even on the exact quotient. */
    internal fun human(bytes: ULong): String {
        val units = listOf("K", "M", "G", "T")
        if (bytes < 1024u) return "${bytes}B"
        var u = 0
        var den = 1024uL
        while (bytes / den >= 1024u && u + 1 < units.size) {
            den *= 1024u
            u++
        }
        val tenths =
            if (bytes <= ULong.MAX_VALUE / 10u) {
                val num = bytes * 10u
                var q = num / den
                val r = num % den
                if (r * 2u > den || (r * 2u == den && q % 2u == 1uL)) q++
                q
            } else {
                (bytes.toDouble() * 10 / den.toDouble()).let { kotlin.math.round(it).toULong() }
            }
        return "${tenths / 10u}.${tenths % 10u}${units[u]}"
    }

    fun lsLong(argv: List<String>): Boolean = argv.drop(1).any { it.startsWith('-') && !it.startsWith("--") && it.substring(1).contains('l') }

    private sealed interface Entry {
        class Link(
            val name: String,
        ) : Entry

        class File(
            val name: String,
            val exec: Boolean,
            val size: ULong,
        ) : Entry
    }

    private const val GROUP_ABOVE = 30

    fun lsL(output: String): String? {
        val dirs = ArrayList<String>()
        val files = ArrayList<Entry>()
        val groups = ArrayList<String>()

        fun flush() {
            val g = ArrayList<String>()
            if (dirs.isNotEmpty()) g += "dirs: ${dirs.joinToString(" ")}"
            g += renderFiles(files.toList())
            files.clear()
            dirs.clear()
            groups += g.joinToString("\n")
        }
        for (line in output.rustLines()) {
            if (line.isEmpty() || line.startsWith("total ")) continue
            if (line.endsWith(':') && !line.contains(' ')) {
                // `ls -l a b` prints `a:` headers.
                if (dirs.isNotEmpty() || files.isNotEmpty()) flush()
                groups += line
                continue
            }
            val it = line.split(' ', '\t').filter { w -> w.isNotEmpty() }
            val perms = it.getOrNull(0) ?: return null
            val kind = perms[0]
            if (perms.byteLen < 10 || kind !in "-dlcbps") return null
            if (it.size < 8) return null
            // Device files print `major, minor`; keep the shape simple and stand down.
            val size = it[4].toULongOrNull() ?: return null
            // Name is everything after the 8th field, spaces included.
            val name = nthFieldRest(line, 8) ?: return null
            if (name == "." || name == "..") continue
            when (kind) {
                'd' -> dirs += "$name/"
                'l' -> files += Entry.Link(name)
                else -> files += Entry.File(name, perms.substring(1).contains('x'), size)
            }
        }
        if (dirs.isNotEmpty() || files.isNotEmpty()) flush()
        return groups.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    private fun renderFiles(entries: List<Entry>): List<String> {
        fun text(
            name: String,
            exec: Boolean,
            size: ULong,
        ) = "$name${if (exec) "*" else ""} ${human(size)}"
        val count = entries.count { it is Entry.File }
        val dots = entries.count { it is Entry.File && it.name.startsWith('.') }
        val loose = ArrayList<String>()
        val dotted = ArrayList<String>()
        val byExt = ArrayList<Pair<String, MutableList<String>>>()
        val pending = ArrayList<Pair<Int, String>>()
        for (e in entries) {
            when {
                e is Entry.Link -> {
                    loose += e.name
                }

                e is Entry.File && dots >= 3 && e.name.startsWith('.') -> {
                    dotted += text(e.name, e.exec, e.size)
                }

                e is Entry.File -> {
                    val i = e.name.lastIndexOf('.').takeIf { it > 0 && it + 1 < e.name.length }
                    if (i != null && count > GROUP_ABOVE) {
                        val ext = e.name.substring(i)
                        val at =
                            byExt.indexOfFirst { it.first == ext }.takeIf { it >= 0 } ?: run {
                                byExt += ext to ArrayList()
                                byExt.size - 1
                            }
                        byExt[at].second += text(e.name.substring(0, i), e.exec, e.size)
                        pending += at to text(e.name, e.exec, e.size)
                    } else {
                        loose += text(e.name, e.exec, e.size)
                    }
                }
            }
        }
        val lines = ArrayList(loose)
        if (dotted.isNotEmpty()) lines += "dotfiles: ${dotted.joinToString(", ")}"
        for ((at, pair) in byExt.withIndex()) {
            val (ext, members) = pair
            if (members.size < 2) {
                lines += pending.filter { it.first == at }.map { it.second }
            } else {
                lines += "*$ext (${members.size}): ${members.joinToString(", ")}"
            }
        }
        return lines
    }

    private fun nthFieldRest(
        line: String,
        n: Int,
    ): String? {
        var rest = line.trimStart()
        repeat(n) {
            val end = rest.indexOfFirst { it.isWhitespace() }.takeIf { it >= 0 } ?: return null
            rest = rest.substring(end).trimStart()
        }
        return rest.ifEmpty { null }
    }
}

/** Rust `strip_prefix`: the rest after [p], or null. */
internal fun String.removePrefixOrNull(p: String): String? = if (startsWith(p)) substring(p.length) else null
