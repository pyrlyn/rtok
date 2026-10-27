// The elvish script: a map from the typed command path to the candidates it offers.
package rtok.completions

internal object ElvishScript {
    fun render(root: CompNode): String {
        val bin = root.name
        return buildString {
            append("\nuse builtin;\nuse str;\n\nset edit:completion:arg-completer[$bin] = {|@words|\n")
            append("    fn spaces {|n|\n        builtin:repeat \$n ' ' | str:join ''\n    }\n")
            append("    fn cand {|text desc|\n        edit:complex-candidate \$text &display=\$text' '(spaces (- 14 (wcswidth \$text)))\$desc\n    }\n")
            append("    var command = '$bin'\n    for word \$words[1..-1] {\n        if (str:has-prefix \$word '-') {\n            break\n        }\n")
            append("        set command = \$command';'\$word\n    }\n    var completions = [\n")
            for ((path, n) in CompTree.walk(root)) {
                append("        &'${path.joinToString(";")}'= {\n")
                n.opts.forEach { o -> o.names.forEach { append("            cand $it ${q(o.help)}\n") } }
                n.children.forEach { append("            cand ${it.name} ${q(it.about)}\n") }
                append("        }\n")
            }
            append("    ]\n    \$completions[\$command]\n}\n")
        }
    }

    private fun q(s: String) = "'" + Completions.firstLine(s).replace("'", "''") + "'"
}
