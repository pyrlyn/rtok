// Port of src/plugins/guard/sed.rs — T557: whether a `sed` invocation can only print. A conservative
// subset: anything the scanner does not recognise (a `w`/`e`/`r` command, `-i`, `-f`, a bracket
// expression inside a delimiter) is refused, which only skips a dedup.
package rtok.plugins.guard

/** [args] are the shell words after `sed`. */
internal fun sedReadOnly(args: List<String>): Boolean {
    val scripts = ArrayList<String>()
    val files = ArrayList<String>()
    val it = args.iterator()
    while (it.hasNext()) {
        val a = it.next()
        when {
            a == "--" -> {
                while (it.hasNext()) files += it.next()
            }

            a.startsWith("--expression=") -> {
                scripts += a.removePrefix("--expression=")
            }

            a == "--expression" || a == "-e" -> {
                if (!it.hasNext()) return false
                scripts += it.next()
            }

            a in setOf("--quiet", "--silent", "--regexp-extended", "--null-data", "--separate") -> {}

            a.startsWith('-') && a.length > 1 -> {
                val cluster = a.substring(1)
                val takes = cluster.endsWith('e')
                val flags = if (takes) cluster.dropLast(1) else cluster
                if (!flags.all { it in "nErszu" }) return false
                if (takes) {
                    if (!it.hasNext()) return false
                    scripts += it.next()
                }
            }

            else -> {
                files += a
            }
        }
    }
    if (scripts.isEmpty() && files.isNotEmpty()) scripts += files.removeAt(0)
    return scripts.isNotEmpty() && scripts.all(::scriptReadOnly)
}

private fun scriptReadOnly(s: String): Boolean {
    val b = s.encodeToByteArray()

    fun at(i: Int): Int? = if (i < b.size) b[i].toInt() and 0xff else null

    fun ws(c: Int) = c == ' '.code || c == '\t'.code || c == '\n'.code || c == '\r'.code || c == 0x0c
    var i = 0
    while (true) {
        while (i < b.size && at(i)!!.let { ws(it) || it == ';'.code || it == '{'.code || it == '}'.code }) i++
        if (i >= b.size) return true
        val first = address(b, i) ?: return false
        val hadAddr = first > i
        i = first
        if (hadAddr && at(i) == ','.code) {
            i++
            if (at(i) == '+'.code || at(i) == '~'.code) i++
            i = address(b, i) ?: return false
        }
        while (at(i)?.let { ws(it) || it == '!'.code } == true) i++
        when (val c = at(i)?.toChar()) {
            'p', 'P', 'd', 'D', 'n', 'N', 'g', 'G', 'h', 'H', 'x', '=' -> {
                i++
            }

            'q', 'Q', 'l' -> {
                i++
                while (at(i)?.let { it in '0'.code..'9'.code } == true) i++
            }

            's' -> {
                val d = at(i + 1)?.takeIf { !(it.toChar().isLetterOrDigit() && it < 128) } ?: return false
                val end = partEnd(b, i + 2, d)?.let { partEnd(b, it, d) } ?: return false
                i = end
                loop@ while (true) {
                    val ch = at(i) ?: break
                    when (ch.toChar()) {
                        'g', 'p', 'i', 'I', 'm', 'M', ' ', in '0'..'9' -> i++
                        ';', '\n', '}' -> break@loop
                        else -> return false
                    }
                }
            }

            else -> {
                return false
            }
        }
        if (at(i)?.let { it != ';'.code && it != '\n'.code && it != '}'.code && it != ' '.code && it != '\t'.code } == true) return false
    }
}

private fun address(
    b: ByteArray,
    start: Int,
): Int? {
    var i = start

    fun at(k: Int): Int? = if (k < b.size) b[k].toInt() and 0xff else null
    val c = at(i)
    when {
        c == '$'.code -> i++
        c != null && c in '0'.code..'9'.code -> while (at(i)?.let { it in '0'.code..'9'.code || it == '~'.code } == true) i++
        c == '/'.code -> i = partEnd(b, i + 1, '/'.code) ?: return null
        c == '\\'.code -> i = partEnd(b, i + 2, at(i + 1) ?: return null) ?: return null
        else -> return i
    }
    while (at(i) == 'I'.code || at(i) == 'M'.code) i++
    return i
}

private fun partEnd(
    b: ByteArray,
    start: Int,
    delim: Int,
): Int? {
    var i = start
    while (i < b.size) {
        val c = b[i].toInt() and 0xff
        when {
            c == '\\'.code -> i++
            c == '['.code -> return null
            c == delim -> return i + 1
        }
        i++
    }
    return null
}
