// Replacement for the `change-preview` crate's text helpers (unified_diff via `similar`, human_bytes, group).
package rtok.sys

object ChangePreview {
    /** Uncoloured unified diff (3 lines of context, `a/`/`b/` headers). Empty when equal. */
    fun unifiedDiff(
        path: String,
        before: String,
        after: String,
    ): String {
        if (before == after) return ""
        val a = splitLines(before)
        val b = splitLines(after)
        val ops = diff(a, b)
        val sb = StringBuilder("--- a/$path\n+++ b/$path\n")
        // group ops into hunks with context radius 3
        val changeIdx = ops.indices.filter { ops[it].kind != ' ' }
        var k = 0
        while (k < changeIdx.size) {
            var start = maxOf(0, changeIdx[k] - 3)
            var end = minOf(ops.size, changeIdx[k] + 4)
            var j = k + 1
            while (j < changeIdx.size && changeIdx[j] - 3 <= end) {
                end = minOf(ops.size, changeIdx[j] + 4)
                j++
            }
            val slice = ops.subList(start, end)
            val oldStart = slice.first().oldIdx
            val newStart = slice.first().newIdx
            val oldLen = slice.count { it.kind != '+' }
            val newLen = slice.count { it.kind != '-' }
            sb
                .append("@@ -")
                .append(range(oldStart, oldLen))
                .append(" +")
                .append(range(newStart, newLen))
                .append(" @@\n")
            for (op in slice) {
                sb.append(op.kind).append(op.text)
                if (!op.text.endsWith("\n")) sb.append("\n\\ No newline at end of file\n")
            }
            k = j
        }
        return sb.toString()
    }

    /** Inserted and deleted line counts between [before] and [after]. */
    fun counts(
        before: String,
        after: String,
    ): Pair<Int, Int> {
        val ops = diff(splitLines(before), splitLines(after))
        return ops.count { it.kind == '+' } to ops.count { it.kind == '-' }
    }

    private fun range(
        start: Int,
        len: Int,
    ) = when (len) {
        1 -> "${start + 1}"
        0 -> "$start,0"
        else -> "${start + 1},$len"
    }

    private class Op(
        val kind: Char,
        val text: String,
        val oldIdx: Int,
        val newIdx: Int,
    )

    private fun splitLines(s: String): List<String> {
        val out = ArrayList<String>()
        var st = 0
        for (i in s.indices) {
            if (s[i] == '\n') {
                out += s.substring(st, i + 1)
                st = i + 1
            }
        }
        if (st < s.length) out += s.substring(st)
        return out
    }

    private fun diff(
        a: List<String>,
        b: List<String>,
    ): List<Op> {
        val n = a.size
        val m = b.size
        val lcs = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) lcs[i][j] = if (a[i] == b[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
        val out = ArrayList<Op>()
        var i = 0
        var j = 0
        while (i < n || j < m) {
            when {
                i < n && j < m && a[i] == b[j] -> {
                    out += Op(' ', a[i], i, j)
                    i++
                    j++
                }

                j < m && (i == n || lcs[i][j + 1] > lcs[i + 1][j]) -> {
                    out += Op('+', b[j], i, j)
                    j++
                }

                else -> {
                    out += Op('-', a[i], i, j)
                    i++
                }
            }
        }
        return out
    }

    /** `1023 B`, `1.0 KB`, `1.5 MB`. */
    fun humanBytes(n: Long): String {
        val kb = 1024.0
        val x = n.toDouble()
        if (x < kb) return "$n B"
        for ((unit, div) in listOf("KB" to kb, "MB" to kb * kb, "GB" to kb * kb * kb)) if (x < div * kb) return "${fmt1(x / div)} $unit"
        return "${fmt1(x / (kb * kb * kb * kb))} TB"
    }

    private fun fmt1(v: Double): String {
        val r = kotlin.math.round(v * 10) / 10
        val s = r.toString()
        return if (s.contains('.')) s else "$s.0"
    }

    /** `1204311` → `1 204 311`. */
    fun group(n: Long): String {
        val d = n.toString()
        return buildString {
            d.forEachIndexed { i, c ->
                if (i > 0 && (d.length - i) % 3 == 0) append(' ')
                append(c)
            }
        }
    }
}
