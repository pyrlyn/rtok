// A reader for the block YAML that host configs use: block mappings and sequences (compact `- key: v` items too),
// flow collections, plain/single/double-quoted scalars, folded plain continuation lines, `|`/`>` block scalars,
// comments, one document (`---` allowed). `>` folds simply (more-indented lines are not kept apart). Anchors, aliases,
// tags, directives and complex keys are refused, not guessed: a caller that cannot read a file never rewrites it.
package yamlaot

object Yaml {
    /** [text] as one value; an empty document is [YamlValue.Null]. */
    fun parse(text: String): YamlValue {
        val raw = text.split("\n").map { it.removeSuffix("\r") }
        val lines = raw.mapIndexedNotNull { n, l -> Syntax.line(n + 1, l) }.toMutableList()
        return Reader(lines, raw).document()
    }
}
