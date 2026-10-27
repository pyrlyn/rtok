// Port of src/measure/dictionary.rs (T403): how many bytes repeated long paths and identifiers take in the message
// array a request carries, and what a cache-safe legend (`~12` for `src/proxy/mod.rs`) could save net. The proxy rows
// hold no request bodies, so the transcripts stand in: they are the history the agent re-sends on every request.
@file:Suppress("MagicNumber")

package rtok.measure

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DictKind(
    /** Bytes of every occurrence of a token that appears at least twice: the ceiling. */
    var repeated: Long = 0,
    /** Bytes the cache-safe legend removes from tool results, net of the definitions. */
    var saved: Long = 0,
)

/** Summed over the sampled requests. */
@Serializable
data class DictionaryRow(
    var requests: Long = 0,
    /** Bytes of the tool inputs and results in the sampled requests. */
    @SerialName("input_bytes") var inputBytes: Long = 0,
    /** What the provider billed as input for the same requests. */
    @SerialName("request_tokens") var requestTokens: Long = 0,
    val paths: DictKind = DictKind(),
    val idents: DictKind = DictKind(),
) {
    /** Nothing repeated: no row, so a report without long paths keeps its bytes. */
    fun isEmpty() = paths.repeated + idents.repeated == 0L

    internal fun merge(o: DictionaryRow) {
        requests += o.requests
        inputBytes += o.inputBytes
        requestTokens += o.requestTokens
        for ((a, b) in listOf(paths to o.paths, idents to o.idents)) {
            a.repeated += b.repeated
            a.saved += b.saved
        }
    }
}

object Dictionary {
    /** Shorter paths and identifiers cost less than their code plus legend line. */
    private const val MIN_PATH = 16
    private const val MIN_IDENT = 12

    /** Requests sampled per session, evenly spaced. */
    private const val CHECKPOINTS = 6

    /** Occurrences per token. */
    internal class Tally {
        val paths = HashMap<String, Long>()
        val idents = HashMap<String, Long>()

        fun add(text: String) =
            scan(text) { isPath, tok ->
                val map = if (isPath) paths else idents
                map[tok] = (map[tok] ?: 0) + 1
            }

        /** Tokens seen at least [min] times as `(isPath, len, count)`, biggest `len × count` first. */
        fun seen(min: Long): List<Triple<Boolean, Long, Long>> =
            listOf(true to paths, false to idents)
                .flatMap { (isPath, map) -> map.filter { it.value >= min }.map { (t, n) -> Triple(isPath, t.length.toLong(), n) } }
                .sortedByDescending { it.second * it.third }
    }

    /** One simulated request: `all` is everything it carries, `results` only the rewritable tool results. */
    internal class Counts {
        var all = Tally()
        var results = Tally()
        var bytes = 0L

        fun clear() {
            all = Tally()
            results = Tally()
            bytes = 0
        }

        fun add(
            text: String,
            rewritable: Boolean,
        ) {
            bytes += utf8Len(text)
            all.add(text)
            if (rewritable) results.add(text)
        }

        /**
         * `repeated`: every occurrence of a token seen twice. `saved`: a code is defined inline at the token's second
         * use in a result (`path{~7}`, `c + 2` extra bytes) and only the third and later uses shrink to `~7`.
         */
        fun saving(): DictionaryRow {
            val row = DictionaryRow(requests = 1, inputBytes = bytes)
            for ((isPath, len, n) in all.seen(2)) (if (isPath) row.paths else row.idents).repeated += len * n
            results.seen(3).forEachIndexed { rank, (isPath, len, n) ->
                val code = 1L + (rank + 1).toString().length
                val gain = (n - 2) * (len - code).coerceAtLeast(0)
                (if (isPath) row.paths else row.idents).saved += (gain - (code + 2)).coerceAtLeast(0)
            }
            return row
        }
    }

    private fun isRun(c: Char) = c.code < 0x80 && (c.isLetterOrDigit() || c == '_' || c == '.' || c == '/' || c == '~' || c == '-')

    /** `scan`: `f(true, path)` or `f(false, identifier)` per candidate; a `/` makes a run a path, `//x` is a URL tail. */
    @Suppress("LoopWithTooManyJumpStatements")
    internal fun scan(
        text: String,
        f: (Boolean, String) -> Unit,
    ) {
        var i = 0
        while (i < text.length) {
            if (!isRun(text[i])) {
                i++
                continue
            }
            val start = i
            while (i < text.length && isRun(text[i])) i++
            val run = text.substring(start, i).trimEnd('.', '-')
            if ('/' in run) {
                if (run.length >= MIN_PATH && !run.startsWith("//") && run.any { it.isLetter() }) f(true, run)
                continue
            }
            run.split('.', '-', '~').filter(::isIdent).forEach { f(false, it) }
        }
    }

    private fun isHex(c: Char) = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    private fun isIdent(s: String): Boolean =
        s.length >= MIN_IDENT &&
            (s[0].isLetter() || s[0] == '_') &&
            !s.all(::isHex) &&
            ('_' in s || (s.any { it in 'a'..'z' } && s.any { it in 'A'..'Z' }))

    /** `fold`: adds the session's sampled requests to [row], counted afresh after a compaction. */
    fun fold(
        parsed: Parsed,
        row: DictionaryRow,
    ) {
        val items =
            (parsed.toolResults.map { Triple(it.turn, it.content, true) } + parsed.toolUses.map { Triple(it.turn, it.input.toString(), false) })
                .sortedBy { it.first }
        val sizes = parsed.usages.map { it.turn to (it.inputTokens + it.cacheCreationInputTokens + it.cacheReadInputTokens) }
        val at =
            (1..CHECKPOINTS)
                .mapNotNull { k -> (sizes.size * k / CHECKPOINTS - 1).takeIf { it >= 0 }?.let(sizes::getOrNull) }
                .fold(mutableListOf<Pair<Int, Long>>()) { acc, p -> acc.apply { if (lastOrNull()?.first != p.first) add(p) } }
        val counts = Counts()
        var c = 0
        var next = 0
        for ((t, tokens) in at) {
            while (next < items.size && items[next].first <= t) {
                while (c < parsed.compactions.size && parsed.compactions[c] <= items[next].first) {
                    c++
                    counts.clear()
                }
                counts.add(items[next].second, items[next].third)
                next++
            }
            while (c < parsed.compactions.size && parsed.compactions[c] <= t) {
                c++
                counts.clear()
            }
            if (counts.bytes > 0) row.merge(counts.saving().also { it.requestTokens = tokens })
        }
    }
}
