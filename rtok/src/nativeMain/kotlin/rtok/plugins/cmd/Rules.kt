// Port of src/plugins/cmd/rules.rs — the TOML rule engine: drop/keep patterns, dedupe, column
// collapse, dir/diag grouping, JSON compaction, stack-trace pinning and the head/tail cut.
package rtok.plugins.cmd

import kotlinx.serialization.json.*
import rtok.config.Config
import rtok.config.Toml
import rtok.plugins.toon.tabularKeys
import rtok.sys.Utf8Order
import rtok.sys.asciiLowercase
import rtok.sys.jsonQuote
import rtok.sys.rustLines
import rtok.sys.scalarList
import rtok.plugins.toon.encode as toonEncode

enum class Group { Off, Dir, Diag }

enum class Dedupe { Off, Adjacent, Normalized }

/** One `[stem]` table of a rules file; [Rule()] is the rule for every stem without one. */
data class Rule(
    val matchCmd: String = "",
    val maxLines: UInt = 40u,
    val head: UInt = 10u,
    val tail: UInt = 10u,
    val drop: List<String> = emptyList(),
    val keep: List<String> = emptyList(),
    val dedupe: Dedupe = Dedupe.Adjacent,
    /** T65.3: fold runs of spaces; on for unruled stems (columnar output), off in TOML rules unless asked. */
    val collapseColumns: Boolean = true,
    val group: Group = Group.Off,
    /** T65.2: first N array elements kept; the rest is `… +K more`. */
    val jsonItems: UInt = 20u,
    /** T65.2: strings longer than this are cut with their character length. */
    val jsonString: UInt = 200u,
)

class Settings internal constructor(
    internal val failTailLines: Int,
    private val rules: List<Rule>,
) {
    fun pick(bin: String): Rule = rules.firstOrNull { it.matchCmd == bin } ?: Rule()

    companion object {
        fun fromConfig(cfg: Config) = load(DiskRuleFiles, cfg.plugins.cmd.rules, cfg.plugins.cmd.rulesDir, cfg.plugins.cmd.failTailLines)

        /** Built-in defaults only (golden tests and fail-open paths without config). */
        fun builtin() = Settings(80, Rules.defaults())

        /** Built-ins, then the user file, then every drop-in in name order; a later file wins per `match_cmd`. Broken files are skipped. */
        fun load(
            files: RuleFiles,
            rulesFile: String,
            rulesDir: String?,
            failTailLines: UInt,
        ): Settings {
            var rules = Rules.defaults()
            val paths = listOf(rulesFile) + (rulesDir?.let { files.dropIns(it) } ?: emptyList())
            for (p in paths) {
                val text = runCatching { files.read(p) }.getOrNull() ?: continue
                Rules.parseStrict(text).onSuccess { rules = mergeRules(rules, it) }
            }
            return Settings(maxOf(failTailLines, 1u).toInt(), rules)
        }

        private fun mergeRules(
            base: List<Rule>,
            user: List<Rule>,
        ): List<Rule> {
            val out = base.toMutableList()
            for (ur in user) {
                val i = out.indexOfFirst { it.matchCmd == ur.matchCmd }
                if (i >= 0) out[i] = ur else out += ur
            }
            return out
        }
    }
}

object Rules {
    private val BUILTIN_KEEP = listOf("error", "warning", "panic", "fail", "traceback")
    internal const val MAX_TRACE_LINES = 400
    private const val FULL_PATH_HITS = 50
    private const val CLOCK_LEN = 5
    private val FIELDS = setOf("max_lines", "head", "tail", "drop", "keep", "dedupe", "collapse_columns", "group", "json_items", "json_string")

    fun defaults(): List<Rule> = parse(DEFAULT_RULES)

    // ---- parsing ----

    private fun dedupeOf(
        v: JsonElement?,
        default: Dedupe,
    ): Result<Dedupe> {
        val p = (v ?: return Result.success(default)) as? JsonPrimitive ?: return Result.failure(IllegalArgumentException("dedupe: expected bool or \"normalized\""))
        if (Toml.isBool(p)) return Result.success(if (p.boolean) Dedupe.Adjacent else Dedupe.Off)
        if (p.isString) {
            return when (p.content) {
                "normalized" -> Result.success(Dedupe.Normalized)
                "true" -> Result.success(Dedupe.Adjacent)
                "false" -> Result.success(Dedupe.Off)
                else -> Result.failure(IllegalArgumentException("dedupe: expected bool or \"normalized\", got \"${p.content}\""))
            }
        }
        return Result.failure(IllegalArgumentException("dedupe: expected bool or \"normalized\""))
    }

    private fun groupOf(v: JsonElement?): Group? =
        when (v) {
            null -> {
                Group.Off
            }

            else -> {
                when ((v as? JsonPrimitive)?.takeIf { it.isString }?.content) {
                    "dir" -> Group.Dir
                    "diag" -> Group.Diag
                    else -> null
                }
            }
        }

    private fun u32(v: JsonElement?): UInt? =
        (v as? JsonPrimitive)
            ?.takeIf { Toml.isInteger(it) }
            ?.long
            ?.takeIf { it in 0..UInt.MAX_VALUE.toLong() }
            ?.toUInt()

    private fun strings(v: JsonElement?): List<String>? = (v as? JsonArray)?.map { e -> (e as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null }

    /** Every table of a rules file, or the first problem (syntax with its line, types, unknown fields). */
    fun parseStrict(s: String): Result<List<Rule>> =
        runCatching {
            val doc =
                try {
                    Toml.parse(s)
                } catch (e: Exception) {
                    throw IllegalArgumentException(e.message ?: "invalid TOML", e)
                }
            doc.map { (k, v) ->
                val t = v as? JsonObject ?: fail("[$k]: expected table")
                t.keys.firstOrNull { it !in FIELDS }?.let { fail("[$k].$it: unknown field") }

                fun num(
                    key: String,
                    d: UInt,
                ) = if (key !in t) d else u32(t[key]) ?: fail("[$k].$key: expected integer ≥ 0")

                fun strs(key: String) = if (key !in t) emptyList() else strings(t[key]) ?: fail("[$k].$key: expected array of strings")
                Rule(
                    matchCmd = k,
                    maxLines = num("max_lines", 40u),
                    head = num("head", 10u),
                    tail = num("tail", 10u),
                    drop = strs("drop"),
                    keep = strs("keep"),
                    dedupe = dedupeOf(t["dedupe"], Dedupe.Adjacent).getOrElse { fail("[$k].${it.message}") },
                    collapseColumns =
                        if ("collapse_columns" !in t) {
                            false
                        } else {
                            (t["collapse_columns"] as? JsonPrimitive)?.takeIf { Toml.isBool(it) }?.boolean
                                ?: fail("[$k].collapse_columns: expected bool")
                        },
                    group = groupOf(t["group"]) ?: fail("[$k].group: expected \"dir\" or \"diag\""),
                    jsonItems = num("json_items", 20u),
                    jsonString = num("json_string", 200u),
                )
            }
        }

    private fun fail(msg: String): Nothing = throw IllegalArgumentException(msg)

    /** The lenient parse of the built-in file: bad values fall back to their defaults. */
    private fun parse(s: String): List<Rule> {
        val doc = runCatching { Toml.parse(s) }.getOrElse { return emptyList() }
        return doc.mapNotNull { (k, v) ->
            val t = v as? JsonObject ?: return@mapNotNull null

            fun num(
                key: String,
                d: UInt,
            ) = u32(t[key]) ?: d

            fun strs(key: String) = (t[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content } ?: emptyList()
            Rule(
                k,
                num("max_lines", 40u),
                num("head", 10u),
                num("tail", 10u),
                strs("drop"),
                strs("keep"),
                dedupeOf(t["dedupe"], Dedupe.Adjacent).getOrDefault(Dedupe.Adjacent),
                (t["collapse_columns"] as? JsonPrimitive)?.takeIf { Toml.isBool(it) }?.boolean ?: false,
                groupOf(t["group"]) ?: Group.Off,
                num("json_items", 20u),
                num("json_string", 200u),
            )
        }
    }

    /** `rtok config check`: one line per rules file that does not parse strictly. */

    /** Lets `rtok config validate` (rtok-config cannot see this module) check the rule files on disk. */
    fun installValidateCheck() = rtok.config.Validate.registerRules { file, dir -> issuesIn(DiskRuleFiles, file, dir) }

    fun issuesIn(
        files: RuleFiles,
        rulesFile: String,
        rulesDir: String,
    ): List<String> {
        val errs = ArrayList<String>()
        for (path in listOf(rulesFile) + files.dropIns(rulesDir)) {
            val text =
                try {
                    files.read(path) ?: continue
                } catch (e: Exception) {
                    errs += "$path: cannot read (${e.message})"
                    continue
                }
            parseStrict(text).onFailure { errs += "$path: ${it.message}" }
        }
        return errs
    }

    // ---- line classes ----

    private fun matchesPat(
        pats: List<String>,
        low: String,
    ) = pats.any { p -> p.split('|').any { it.isNotEmpty() && low.contains(it.asciiLowercase()) } }

    private fun isWarnLevel(low: String) = listOf("level=warn", "lvl=warn", "[warn]", "\"level\":\"warn\"").any { low.contains(it) }

    private fun isKeep(
        low: String,
        rule: Rule,
    ) = BUILTIN_KEEP.any { low.contains(it) } || isWarnLevel(low) || matchesPat(rule.keep, low) ||
        when (rule.group) {
            Group.Dir -> low.contains(" files): ")
            Group.Diag -> low.contains(" ×") && low.contains(": ")
            Group.Off -> false
        }

    private fun isDrop(
        low: String,
        rule: Rule,
    ) = matchesPat(rule.drop, low)

    /** Runs of spaces inside a line fold to one; leading indentation is kept and a trailing run goes. */
    internal fun collapseColumns(line: String): String {
        val trimmed = line.trimStart()
        val out = StringBuilder(line.length).append(line, 0, line.length - trimmed.length)
        var spaces = 0
        for (c in trimmed) {
            if (c == ' ') {
                spaces++
                continue
            }
            if (spaces > 0) {
                out.append(' ')
                spaces = 0
            }
            out.append(c)
        }
        return out.toString()
    }

    private fun String.indented() = startsWith(' ') || startsWith('\t')

    /** Lines that belong to a Python/Rust/Go/Java/JS stack trace. */
    internal fun traceBlocks(lines: List<String>): BooleanArray {
        val keep = BooleanArray(lines.size)
        var i = 0

        fun indentedFrom(from: Int): Int {
            var j = from
            while (j < lines.size && lines[j].indented()) keep[j++] = true
            return j
        }
        while (i < lines.size) {
            val low = lines[i].asciiLowercase()
            when {
                low.startsWith("traceback (most recent call last):") -> {
                    keep[i] = true
                    i = indentedFrom(i + 1)
                }

                low.contains("panicked at") -> {
                    keep[i] = true
                    var j = i + 1
                    // The panic message sits directly under the header; from `stack backtrace:` on, the indented
                    // frames are kept until the first non-indented line (the `note:` trailer ends the block unkept).
                    if (j < lines.size && !lines[j].indented()) {
                        val l = lines[j].asciiLowercase()
                        if (!l.startsWith("stack backtrace:") && !l.startsWith("note:") && !l.startsWith("error") && !l.startsWith("warning")) keep[j++] = true
                    }
                    while (j < lines.size) {
                        val ll = lines[j].asciiLowercase()
                        if (ll.startsWith("stack backtrace:")) {
                            keep[j] = true
                            j = indentedFrom(j + 1)
                            break
                        }
                        if (ll.startsWith("note:") || ll.startsWith("error") || ll.startsWith("warning")) break
                        if (lines[j].indented() || j == i + 1) {
                            keep[j++] = true
                            continue
                        }
                        break
                    }
                    i = j
                }

                low.startsWith("goroutine ") && low.contains('[') -> {
                    keep[i] = true
                    var j = i + 1
                    while (j < lines.size && (lines[j].indented() || lines[j].trimEnd().endsWith("()"))) keep[j++] = true
                    i = j
                }

                low.contains("exception in thread") -> {
                    keep[i] = true
                    var j = i + 1
                    while (j < lines.size && lines[j].startsWith('\t')) keep[j++] = true
                    i = j
                }

                low.contains("error") && lines.getOrNull(i + 1)?.startsWith("    at ") == true -> {
                    keep[i] = true
                    var j = i + 1
                    while (j < lines.size && (lines[j].startsWith("    at ") || lines[j].startsWith('\t'))) keep[j++] = true
                    i = j
                }

                else -> {
                    i++
                }
            }
        }
        return keep
    }

    internal fun capTraceLines(kept: BooleanArray) {
        var n = 0
        for (i in kept.indices) {
            if (kept[i]) {
                n++
                if (n > MAX_TRACE_LINES) kept[i] = false
            }
        }
    }

    // ---- normalized dedupe ----

    private fun Char.digit() = this in '0'..'9'

    private fun Char.hex() = digit() || this in 'a'..'f' || this in 'A'..'F'

    private fun Char.alpha() = this in 'a'..'z' || this in 'A'..'Z'

    private fun String.allIn(
        r: IntRange,
        p: (Char) -> Boolean,
    ) = r.all { p(this[it]) }

    /** [line] with timestamps, pids, uuids, long hex and durations replaced by placeholders. */
    internal fun normalizeLineKey(line: String): String {
        val out = StringBuilder(line.length)
        var i = 0
        while (i < line.length) {
            val tok = placeholderToken(line, i)
            if (tok != null) {
                out.append(tok.second)
                i += tok.first
                continue
            }
            out.append(line[i++])
        }
        return out.toString()
    }

    /** (length, placeholder) of a token starting at [at] of [s]. */
    internal fun placeholderToken(
        s: String,
        at: Int = 0,
    ): Pair<Int, String>? {
        val n = s.length - at

        fun c(k: Int) = s[at + k]

        fun all(
            r: IntRange,
            p: (Char) -> Boolean,
        ) = r.all { p(c(it)) }
        if (n >= 19 && c(4) == '-' && c(7) == '-' && (c(10) == 'T' || c(10) == ' ') && c(13) == ':' && c(16) == ':' &&
            all(0..3) { it.digit() } && all(5..6) { it.digit() } && all(8..9) { it.digit() } && all(11..12) { it.digit() } && all(14..15) { it.digit() } && all(17..18) { it.digit() }
        ) {
            return 19 to "<TS>"
        }
        if (n >= 20 && c(2) == '/' && c(6) == '/' && c(11) == ':' && c(14) == ':' && c(17) == ':' && all(0..1) { it.digit() } && all(7..10) { it.digit() } &&
            all(12..13) { it.digit() } && all(15..16) { it.digit() } && all(18..19) { it.digit() } && all(3..5) { it.alpha() }
        ) {
            return 20 to "<TS>"
        }
        if (s.startsWith("pid=", at)) {
            var j = 4
            while (j < n && c(j).digit()) j++
            if (j > 4) return j to "pid=<PID>"
        }
        uuidAt(s, at)?.let { return it to "<ID>" }
        longHexAt(s, at)?.let { return it to "<HEX>" }
        if (n > 0 && c(0).digit()) durationAt(s, at)?.let { return it to "<DUR>" }
        return null
    }

    private fun uuidAt(
        s: String,
        at: Int,
    ): Int? {
        var pos = at
        for ((len, sep) in listOf(8 to '-', 4 to '-', 4 to '-', 4 to '-', 12 to null)) {
            if (pos + len > s.length || !(pos until pos + len).all { s[it].hex() }) return null
            pos += len
            if (sep != null) {
                if (pos >= s.length || s[pos] != sep) return null
                pos++
            }
        }
        return pos - at
    }

    private fun longHexAt(
        s: String,
        at: Int,
    ): Int? {
        if (s.startsWith("0x", at) || s.startsWith("0X", at)) return null
        var n = 0
        while (at + n < s.length && s[at + n].hex()) n++
        return n.takeIf { it >= 16 }
    }

    private fun durationBoundary(
        s: String,
        at: Int,
    ) = at >= s.length || !(s[at].alpha() || s[at].digit())

    private fun durationAt(
        s: String,
        at: Int,
    ): Int? {
        val n = s.length - at
        // `d:d:d` (`1:2:3`).
        if (n >= CLOCK_LEN && s[at].digit() && s[at + 1] == ':' && s[at + 2].digit() && s[at + 3] == ':' && s[at + 4].digit() && durationBoundary(s, at + CLOCK_LEN)) return CLOCK_LEN
        var i = 0
        while (at + i < s.length && (s[at + i].digit() || s[at + i] == '.')) i++
        if (i == 0) return null
        if (s.startsWith("ms", at + i) && durationBoundary(s, at + i + 2)) return i + 2
        if (s.startsWith("s", at + i) && durationBoundary(s, at + i + 1)) return i + 1
        return null
    }

    /** One line per normalized key at its first position, with the count and the other line numbers. */
    internal fun normalizedDedupe(lines: List<String>): List<String> {
        val keys = lines.map(::normalizeLineKey)
        val first = HashMap<String, Int>()
        val also = HashMap<String, MutableList<Int>>()
        for ((i, k) in keys.withIndex()) {
            if (k in first) {
                also.getValue(k) += i + 1
            } else {
                first[k] = i
                also[k] = ArrayList()
            }
        }
        return lines.indices.filter { first[keys[it]] == it }.map { i ->
            val more = also.getValue(keys[i])
            if (more.isEmpty()) lines[i] else "${lines[i]} (×${more.size + 1}, also lines ${more.joinToString(", ")})"
        }
    }

    private fun dedupe(lines: List<String>): List<String> {
        val out = ArrayList<String>()
        var prev: String? = null
        var n = 0

        fun flush() {
            prev?.let { out += if (n > 1) "$it (×$n)" else it }
            prev = null
            n = 0
        }
        for (line in lines) {
            if (prev == line) {
                n++
                continue
            }
            flush()
            prev = line
            n = 1
        }
        flush()
        return out
    }

    // ---- grouping ----

    private fun pathLine(line: String): Pair<String, String>? {
        var s = line.trim()
        if (s.isEmpty() || s.any { it.isWhitespace() } || s.startsWith('-')) return null
        s = s.removePrefix("./")
        val drive = s.getOrNull(1) == ':'
        if (s.contains(':') && !drive) return null
        s = s.replace('\\', '/')
        val cut = s.lastIndexOf('/')
        if (cut >= 0) {
            val n = s.substring(cut + 1)
            return if (n.isEmpty() || n == "." || n == "..") null else s.substring(0, cut) to n
        }
        return if (s.contains('.') && s != "." && s != "..") "" to s else null
    }

    private class Fold(
        val key: String,
        val msg: String,
        val extra: MutableList<String> = ArrayList(),
        var n: Int = 0,
    )

    /** Lines [parse] reads as (key, message, extra) fold into one [fmt] line at the first one's position. */
    private fun fold(
        lines: List<String>,
        parse: (String) -> Triple<String, String, String>?,
        fmt: (String, String, List<String>, Int) -> String,
    ): List<String> {
        val groups = ArrayList<Fold>()
        val index = HashMap<String, Int>()
        val out = ArrayList<Any>() // Int = group index, String = untouched line
        for (line in lines) {
            val (key, msg, extra) =
                parse(line) ?: run {
                    out += line
                    null
                } ?: continue
            val i =
                index.getOrPut(key) {
                    groups += Fold(key, msg)
                    out += groups.size - 1
                    groups.size - 1
                }
            groups[i].n++
            if (extra.isNotEmpty()) groups[i].extra += extra
        }
        return out.map { if (it is Int) groups[it].let { g -> fmt(g.key, g.msg, g.extra, g.n) } else it as String }
    }

    internal fun groupDir(lines: List<String>): List<String> {
        // Bare names (`ls`) have no directory to lose, so only `dir/name` hits count.
        val hits = lines.count { l -> pathLine(l)?.first?.isNotEmpty() == true }
        if (hits in 1..FULL_PATH_HITS) return lines
        return fold(lines, { l -> pathLine(l)?.let { (d, n) -> Triple(d, "", n) } }) { dir, _, names, n ->
            var list = names.take(3).joinToString(", ")
            if (names.size > 3) list += " …"
            "${dir.ifEmpty { "." }}/ ($n files): $list".replaceFirst("./ ", ". ")
        }
    }

    private fun isExc(s: String) =
        s.firstOrNull()?.let { it in 'A'..'Z' } == true && s.all { it.alpha() || it.digit() } &&
            (s.endsWith("Error") || s.endsWith("Exception") || s.endsWith("Warning"))

    private fun locBefore(
        t: String,
        p: Int,
    ): String {
        val s =
            t
                .substring(0, p)
                .trim()
                .trimEnd(':')
                .trim()
        val open = s.indexOf('(').takeIf { it >= 0 } ?: return s
        val file = s.substring(0, open)
        val line = s.substring(open + 1).split(',', ')')[0]
        return if (file.isEmpty() || line.isEmpty()) s else "$file:$line"
    }

    private fun coded(
        t: String,
        needle: String,
        prefix: String,
    ): Triple<String, String, String>? {
        val p = t.indexOf(needle).takeIf { it >= 0 } ?: return null
        val rest = t.substring(p + needle.length)
        val n = rest.indexOfFirst { !it.digit() }.let { if (it < 0) rest.length else it }
        if (n == 0) return null
        return Triple(prefix + rest.substring(0, n), rest.substring(n).trimStart(':', ' ').trim(), locBefore(t, p))
    }

    /** (code, message, location) of a compiler/linter/test diagnostic line. */
    internal fun parseDiag(line: String): Triple<String, String, String>? {
        val t = line.trim()
        val p = t.indexOf("error[").takeIf { it >= 0 } ?: t.indexOf("warning[").takeIf { it >= 0 }
        if (p != null) {
            val kindEnd = t.indexOf('[', p) + 1
            val end = t.indexOf(']', kindEnd).takeIf { it >= 0 } ?: return null
            val key = t.substring(kindEnd, end).ifEmpty { return null }
            return Triple(key, t.substring(end + 1).trimStart(':', ' ').trim(), "")
        }
        (coded(t, "error TS", "TS") ?: coded(t, "error CS", "CS"))?.let { return it }
        val parts = t.split(' ', '\t').filter { it.isNotEmpty() }
        if (parts.size >= 4 && (parts[1] == "error" || parts[1] == "warning")) {
            val loc = parts[0]
            val c = loc.indexOf(':').takeIf { it >= 0 } ?: return null
            if (loc.substring(0, c).all { it.digit() } && loc.substring(c + 1).all { it.digit() }) {
                return Triple(parts.last(), parts.subList(2, parts.size - 1).joinToString(" "), loc)
            }
        }
        parseLocated(t)?.let { return it }
        t.removePrefixOrNull("FAILED ")?.let { rest ->
            val d = rest.indexOf(" - ")
            if (d >= 0) {
                val loc = rest.substring(0, d)
                val err = rest.substring(d + 3)
                val c = err.indexOf(": ")
                if (c >= 0 && isExc(err.substring(0, c))) return Triple(err.substring(0, c), err.substring(c + 2), loc)
            }
        }
        val c = t.indexOf(": ")
        if (c >= 0) {
            val cls =
                t
                    .substring(0, c)
                    .trim()
                    .removePrefixRepeated("E ")
                    .trim()
            if (isExc(cls)) return Triple(cls, t.substring(c + 2), "")
        }
        return null
    }

    /** `path:line[:col]: CODE msg`, rubocop, mypy `[code]` and golangci `(linter)` shapes. */
    internal fun parseLocated(t: String): Triple<String, String, String>? {
        val sep = t.indexOf(": ").takeIf { it >= 0 } ?: return null
        val loc = t.substring(0, sep)
        var rest = t.substring(sep + 2)
        // `path:line` or `path:line:col`; a bare word or a `key: value` line is not a diagnostic.
        val lc = loc.lastIndexOf(':').takeIf { it >= 0 } ?: return null
        val path = loc.substring(0, lc)
        val last = loc.substring(lc + 1)
        if (path.isEmpty() || loc.any { it.isWhitespace() } || last.isEmpty() || !last.all { it.digit() }) return null
        rest = rest.trim()
        val code = rest.split(' ', '\t').firstOrNull { it.isNotEmpty() } ?: return null
        if (code.length in 2..7 && code[0] in 'A'..'Z' && code.all { it in 'A'..'Z' || it.digit() } && code.any { it.digit() }) {
            return Triple(
                code,
                rest
                    .substring(code.length)
                    .trimStart()
                    .removePrefixRepeated("[*]")
                    .trim(),
                loc,
            )
        }
        // `rubocop`: `C: Style/Foo: msg`, `W: [Correctable] Lint/Bar: msg`.
        val rs = rest.indexOf(": ")
        if (rs == 1 && rest[0] in 'A'..'Z') {
            val body = rest.substring(3).let { it.removePrefixOrNull("[Correctable] ") ?: it }
            val bc = body.indexOf(": ")
            if (bc >= 0) {
                val cop = body.substring(0, bc)
                if (cop.contains('/') && cop[0] in 'A'..'Z' && cop.none { it.isWhitespace() }) return Triple(cop, body.substring(bc + 2).trim(), loc)
            }
        }
        val msg = rest.removePrefixOrNull("error: ") ?: rest.removePrefixOrNull("warning: ")
        if (msg != null && msg.endsWith(']')) {
            val open = msg.lastIndexOf("  [")
            if (open >= 0) {
                val key = msg.substring(open + 3, msg.length - 1)
                if (key.isNotEmpty() && key.none { it.isWhitespace() }) return Triple(key, msg.substring(0, open).trim(), loc)
            }
        }
        if (rest.endsWith(')')) {
            val open = rest.lastIndexOf(" (")
            if (open >= 0) {
                val key = rest.substring(open + 2, rest.length - 1)
                if (key.isNotEmpty() && key.all { it in 'a'..'z' || it == '-' || it.digit() }) return Triple(key, rest.substring(0, open).trim(), loc)
            }
        }
        return null
    }

    internal fun groupDiag(lines: List<String>): List<String> =
        fold(lines, ::parseDiag) { key, msg, locs, n ->
            var locS = locs.take(3).joinToString(", ")
            if (locs.size > 3) locS += ", …"
            if (locS.isEmpty()) "$key ×$n: $msg" else "$key ×$n: $msg ($locS)"
        }

    // ---- JSON ----

    /** A `{`/`[` body as serde_json reads it: object keys in byte order (no `preserve_order`). */
    internal fun parseJsonBody(s: String): JsonElement? {
        val t = s.trim()
        if (t.firstOrNull().let { it != '{' && it != '[' }) return null
        return runCatching { sortKeys(Json.parseToJsonElement(t)) }.getOrNull()
    }

    private fun sortKeys(e: JsonElement): JsonElement =
        when (e) {
            is JsonObject -> JsonObject(LinkedHashMap<String, JsonElement>().also { m -> e.keys.sortedWith(Utf8Order).forEach { m[it] = sortKeys(e.getValue(it)) } })
            is JsonArray -> JsonArray(e.map(::sortKeys))
            else -> e
        }

    private fun fmtTop(
        v: JsonElement,
        items: Int,
        stringMax: Int,
    ): String =
        when (v) {
            is JsonObject -> {
                v.mapNotNull { (k, x) -> fmtVal(x, items, stringMax)?.let { "$k: $it" } }.joinToString("\n")
            }

            is JsonArray -> {
                val lines = v.take(items).mapNotNull { fmtVal(it, items, stringMax) }.toMutableList()
                if (v.size > items) lines += "… +${v.size - items} more"
                val rows = lines.joinToString("\n")
                toonRows(v, items)?.takeIf { it.byteLen < rows.byteLen } ?: rows
            }

            else -> {
                fmtVal(v, items, stringMax) ?: ""
            }
        }

    private fun toonRows(
        arr: JsonArray,
        items: Int,
    ): String? {
        val keys = tabularKeys(arr, 2) ?: return null
        var out = toonEncode(arr.take(items), keys)
        if (arr.size > items) out += "\n… +${arr.size - items} more"
        return out
    }

    private fun fmtVal(
        v: JsonElement,
        items: Int,
        stringMax: Int,
    ): String? =
        when (v) {
            is JsonNull -> {
                null
            }

            is JsonPrimitive -> {
                if (!v.isString) {
                    v.content
                } else if (v.content.isEmpty()) {
                    null
                } else {
                    fmtString(v.content, stringMax)
                }
            }

            is JsonArray -> {
                if (v.isEmpty()) {
                    null
                } else {
                    val shown = v.take(items).mapNotNull { fmtVal(it, items, stringMax) }
                    val extra = maxOf(0, v.size - items)
                    "[${shown.joinToString(", ")}]" + if (extra > 0) " … +$extra more" else ""
                }
            }

            is JsonObject -> {
                v.mapNotNull { (k, x) -> fmtVal(x, items, stringMax)?.let { "$k: $it" } }.takeIf { it.isNotEmpty() }?.let { "{${it.joinToString(", ")}}" }
            }
        }

    private fun fmtString(
        s: String,
        max: Int,
    ): String {
        val chars = s.scalarList()
        if (chars.size <= max) return jsonQuote(s)
        return "${jsonQuote(chars.take(max).joinToString("") + "…")} (${chars.size})"
    }

    // ---- apply ----

    fun apply(
        settings: Settings,
        output: String,
        exit: Int,
        rule: Rule,
        archiveId: String,
    ): String =
        // The parse is skipped on a failed run: that path never reads the body as JSON.
        applyParsed(settings, output, if (exit == 0) parseJsonBody(output) else null, exit, rule, archiveId)

    internal fun applyParsed(
        settings: Settings,
        output: String,
        json: JsonElement?,
        exit: Int,
        rule: Rule,
        archiveId: String,
    ): String {
        if (exit != 0) return output.rustLines().takeLast(settings.failTailLines).joinToString("\n")
        var lines: MutableList<String> =
            if (json != null) {
                // T65.2: the JSON rewrite replaces every line of the payload, so drop/keep/dedupe cannot change it.
                fmtTop(json, maxOf(rule.jsonItems, 1u).toInt(), maxOf(rule.jsonString, 1u).toInt()).rustLines().toMutableList()
            } else {
                var ls = output.rustLines().filter { l -> l.asciiLowercase().let { isKeep(it, rule) || !isDrop(it, rule) } }
                // T65.4: with a trace block in the output the columnar pass stands down.
                if (rule.collapseColumns && !traceBlocks(ls).any { it }) ls = ls.map(::collapseColumns)
                when (rule.dedupe) {
                    Dedupe.Off -> ls
                    Dedupe.Adjacent -> dedupe(ls)
                    Dedupe.Normalized -> normalizedDedupe(ls)
                }.toMutableList()
            }
        // One pass, after the JSON rewrite: a grouped line is a summary, not a path or a diagnostic.
        when (rule.group) {
            Group.Dir -> {
                lines = groupDir(lines).toMutableList()
            }

            Group.Diag -> {
                lines = groupDiag(lines).toMutableList()
            }

            Group.Off -> {}
        }
        val max = maxOf(rule.maxLines, 1u).toInt()
        if (lines.size <= max) return lines.joinToString("\n")
        // T683: the cut is certain, so state the pairs every record shares once.
        val shared = logfmtFactor(lines) { isKeep(it.asciiLowercase(), rule) }
        return cut(lines, shared, rule, max, archiveId)
    }

    /** The head/tail cut with keep lines and whole trace blocks pinned, gaps as `… N lines omitted` markers. */
    private fun cut(
        lines: List<String>,
        shared: String?,
        rule: Rule,
        max: Int,
        archiveId: String,
    ): String {
        val traceKept = traceBlocks(lines).also(::capTraceLines)
        val head = minOf(rule.head, rule.maxLines).toInt()
        val tail = (if (rule.maxLines > rule.head) minOf(rule.tail, rule.maxLines - rule.head) else 0u).toInt()
        val total = lines.size
        val take = BooleanArray(total) { it < head || it >= total - tail }
        lines.forEachIndexed { i, l -> if (isKeep(l.asciiLowercase(), rule)) take[i] = true }
        val picked = ArrayList<String>()
        var omitted = 0
        // T683: the lines behind the pending marker, for its logfmt field summary (logfmt output only).
        val run = ArrayList<String>()
        var lastPushedTrace = false
        // Suffix count of trace lines still ahead (T65.4).
        val traceRestFrom = IntArray(total + 1)
        for (i in total - 1 downTo 0) traceRestFrom[i] = traceRestFrom[i + 1] + if (traceKept[i]) 1 else 0
        for ((i, line) in lines.withIndex()) {
            // T65.4: a trace block's frames are never omitted; only its own length counts against `max_lines`.
            if (traceKept[i]) {
                if (omitted > 0) {
                    picked += omittedMarker(omitted, archiveId, run)
                    omitted = 0
                    run.clear()
                }
                picked += line
                lastPushedTrace = true
                continue
            }
            if (take[i] && picked.size < max) {
                if (omitted > 0) {
                    // Past a spent budget with no trace ahead, fold everything (this line included) and stop.
                    val fold = picked.size + 1 >= max && traceRestFrom[i + 1] == 0
                    val count = omitted + if (fold) total - i else 0
                    picked += omittedMarker(count, archiveId, if (fold) emptyList() else run)
                    omitted = 0
                    run.clear()
                    lastPushedTrace = false
                    if (fold) break
                }
                picked += line
                lastPushedTrace = false
            } else {
                omitted++
                if (shared != null) run += line
            }
        }
        if (omitted > 0) {
            // Fold the trailer into a full budget only when the last pushed line is not a trace line.
            if (picked.size >= max && !lastPushedTrace) {
                val popped = picked.removeAt(picked.size - 1)
                if (shared != null) run += popped
                omitted++
            }
            picked += omittedMarker(omitted, archiveId, run)
        }
        if (shared != null) picked.add(0, shared)
        return picked.joinToString("\n")
    }

    private fun omittedMarker(
        n: Int,
        archiveId: String,
        run: List<String>,
    ): String {
        var m = if (archiveId.isEmpty()) "… $n lines omitted" else "… $n lines omitted (expand $archiveId)"
        if (run.size == n) logfmtSummary(run)?.let { m += " [$it]" }
        return m
    }
}
