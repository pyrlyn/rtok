package rtok.treesitter

/** Text predicates every tree-sitter binding applies: `eq?`, `match?`, `any-of?` and their `not-` forms. */
private val TEXT_OPS = setOf("eq?", "not-eq?", "any-eq?", "any-not-eq?", "match?", "not-match?", "any-match?", "any-not-match?", "any-of?", "not-any-of?")

private fun text(
    source: ByteArray,
    node: Node,
): String = source.decodeToString(node.startByte, node.endByte)

private fun holds(
    p: Predicate,
    m: QueryMatch,
    source: ByteArray,
    regexes: MutableMap<String, Regex>,
): Boolean {
    val first = p.args.firstOrNull() as? PredicateArg.CaptureRef ?: return true
    val nodes = m.nodes(first.name)
    if (nodes.isEmpty()) return true
    val any = p.op.startsWith("any-") && p.op != "any-of?"
    val negate = p.op.startsWith("not-") || p.op.startsWith("any-not-")
    val test: (String) -> Boolean =
        when (p.op.removePrefix("any-").removePrefix("not-")) {
            "eq?" -> {
                when (val second = p.args.getOrNull(1)) {
                    is PredicateArg.Literal -> { s -> s == second.value }
                    is PredicateArg.CaptureRef -> { s -> m.nodes(second.name).any { text(source, it) == s } }
                    null -> { _ -> true }
                }
            }

            "match?" -> {
                val pattern = (p.args.getOrNull(1) as? PredicateArg.Literal)?.value ?: return true
                val re = regexes.getOrPut(pattern) { Regex(pattern) }
                ({ s -> re.containsMatchIn(s) })
            }

            "of?" -> {
                val set =
                    p.args
                        .drop(1)
                        .filterIsInstance<PredicateArg.Literal>()
                        .map { it.value }
                        .toSet()
                ({ s -> s in set })
            }

            else -> {
                return true
            }
        }
    val results = nodes.map { test(text(source, it)) != negate }
    return if (any) results.any { it } else results.all { it }
}

/** [Query.rawMatches] filtered by the standard text predicates against [source]. */
fun Query.matches(
    node: Node,
    source: ByteArray,
): Sequence<QueryMatch> {
    val regexes = HashMap<String, Regex>()
    val byPattern = HashMap<Int, List<Predicate>>()
    return rawMatches(node).filter { m ->
        byPattern.getOrPut(m.pattern) { predicates(m.pattern).filter { it.op in TEXT_OPS } }.all { holds(it, m, source, regexes) }
    }
}

/** Parses [source] and runs [block] on the tree, freeing it afterwards. */
inline fun <T> Grammar.withTree(
    source: ByteArray,
    block: (Tree) -> T,
): T? = parse(source)?.use(block)
