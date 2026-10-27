package globset

/** globset 0.4.20 `Parser`: the glob text to tokens, same branches/alternates stack. */
internal class Parser(
    private val glob: String,
    private val opts: GlobOptions,
) {
    private val alternatesStack = ArrayList<Int>()
    private val branches = arrayListOf(ArrayList<Token>())
    private val chars = Cursor(glob)
    private var foundUnclosedClass = false

    fun parse(): List<Token> {
        while (true) {
            val c = chars.bump() ?: break
            when {
                c == '?' -> pushToken(Token.Any)
                c == '*' -> parseStar()
                c == '[' && !foundUnclosedClass -> parseClass()
                c == '{' -> pushAlternate()
                c == '}' -> popAlternate()
                c == ',' -> parseComma()
                c == '\\' -> parseBackslash()
                else -> pushToken(Token.Literal(c))
            }
        }
        if (branches.size > 1) throw error(GlobErrorKind.UnclosedAlternates)
        return branches.single()
    }

    private fun error(kind: GlobErrorKind) = GlobError(glob, kind)

    private fun pushAlternate() {
        alternatesStack.add(branches.size)
        branches.add(ArrayList())
    }

    private fun popAlternate() {
        val start = alternatesStack.removeLastOrNull() ?: throw error(GlobErrorKind.UnopenedAlternates)
        val taken = branches.subList(start, branches.size)
        val alts = Token.Alternates(taken.map { it.toList() })
        taken.clear()
        pushToken(alts)
    }

    private fun top(): MutableList<Token> = branches.lastOrNull() ?: throw error(GlobErrorKind.UnopenedAlternates)

    private fun pushToken(tok: Token) {
        top().add(tok)
    }

    private fun parseComma() {
        if (alternatesStack.isEmpty()) pushToken(Token.Literal(',')) else branches.add(ArrayList())
    }

    private fun parseBackslash() {
        if (opts.backslashEscape) {
            pushToken(Token.Literal(chars.bump() ?: throw error(GlobErrorKind.DanglingEscape)))
        } else {
            pushToken(Token.Literal('\\'))
        }
    }

    private fun twoStars() {
        pushToken(Token.ZeroOrMore)
        pushToken(Token.ZeroOrMore)
    }

    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    private fun parseStar() {
        val prev = chars.prev
        if (chars.peek() != '*') {
            pushToken(Token.ZeroOrMore)
            return
        }
        chars.bump()
        if (top().isEmpty()) {
            val next = chars.peek()
            if (next != null && next != '/') {
                twoStars()
            } else {
                pushToken(Token.RecursivePrefix)
                chars.bump()
            }
            return
        }
        val opensBranch = branches.size > 1 && (prev == ',' || prev == '{')
        if (prev != '/' && !opensBranch) {
            twoStars()
            return
        }
        val next = chars.peek()
        val isSuffix =
            when {
                next == null -> {
                    chars.bump()
                    true
                }

                (next == ',' || next == '}') && branches.size >= 2 -> {
                    true
                }

                next == '/' -> {
                    chars.bump()
                    false
                }

                else -> {
                    twoStars()
                    return
                }
            }
        when (top().removeLast()) {
            Token.RecursivePrefix -> pushToken(Token.RecursivePrefix)
            Token.RecursiveSuffix -> pushToken(Token.RecursiveSuffix)
            else -> pushToken(if (isSuffix) Token.RecursiveSuffix else Token.RecursiveZeroOrMore)
        }
    }

    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth")
    private fun parseClass() {
        val saved = chars.save()
        val ranges = ArrayList<Pair<Char, Char>>()
        val negated = (chars.peek() == '!' || chars.peek() == '^').also { if (it) chars.bump() }
        var first = true
        var inRange = false
        while (true) {
            val c = chars.bump()
            if (c == null) {
                if (!opts.allowUnclosedClass) throw error(GlobErrorKind.UnclosedClass)
                chars.restore(saved)
                foundUnclosedClass = true
                pushToken(Token.Literal('['))
                return
            }
            when {
                c == ']' && !first -> {
                    break
                }

                c == ']' -> {
                    ranges.add(']' to ']')
                }

                c == '-' && first -> {
                    ranges.add('-' to '-')
                }

                c == '-' && inRange -> {
                    extendLast(glob, ranges, '-')
                    inRange = false
                }

                c == '-' -> {
                    inRange = true
                }

                inRange -> {
                    extendLast(glob, ranges, c)
                    inRange = false
                }

                else -> {
                    ranges.add(c to c)
                }
            }
            first = false
        }
        if (inRange) ranges.add('-' to '-')
        pushToken(Token.Class(negated, ranges))
    }
}

private fun extendLast(
    glob: String,
    ranges: MutableList<Pair<Char, Char>>,
    end: Char,
) {
    val start = ranges.last().first
    ranges[ranges.lastIndex] = start to end
    if (end < start) throw GlobError(glob, GlobErrorKind.InvalidRange(start, end))
}

/** Rust's `chars().peekable()` plus globset's `prev`/`cur`. */
private class Cursor(
    private val text: String,
) {
    private var index = 0
    var prev: Char? = null
        private set
    private var cur: Char? = null

    fun bump(): Char? {
        prev = cur
        cur = text.getOrNull(index)
        if (cur != null) index++
        return cur
    }

    fun peek(): Char? = text.getOrNull(index)

    fun save(): Triple<Int, Char?, Char?> = Triple(index, prev, cur)

    fun restore(s: Triple<Int, Char?, Char?>) {
        index = s.first
        prev = s.second
        cur = s.third
    }
}
