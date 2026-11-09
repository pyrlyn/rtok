package rtok.treesitter.native

import rtok.treesitter.*
import kotlin.test.*

class NativeTreeSitterTest {
    private val ts = NativeTreeSitter

    @Test fun everyGrammarParsesEmptyInput() {
        assertTrue(ts.grammars.size >= 32, "grammars: ${ts.grammars.map { it.name }}")
        for (g in ts.grammars) assertNotNull(g.parse(ByteArray(0)), g.name).close()
    }

    @Test fun parsesRustAndWalksFields() {
        val src = "fn main() { let x = 1; }".encodeToByteArray()
        ts.grammar("rust")!!.withTree(src) { tree ->
            val f = tree.root.namedChild(0)!!
            assertEquals("function_item", f.kind)
            assertEquals("main", f.childByFieldName("name")!!.let { src.decodeToString(it.startByte, it.endByte) })
            assertEquals(f.childCount, f.children().size)
            assertFalse(tree.root.hasError)
            assertTrue(tree.root.sexp().startsWith("(source_file"))
        }
    }

    @Test fun javascriptTagsCarryAdjacentStrippedDocs() {
        val g = ts.grammar("javascript")!!
        val src = "// far\n\n// Adds one.\nfunction inc(x) { return x + 1 }\n".encodeToByteArray()
        val tags = TagsConfiguration(g, g.tagsQuery!!, g.localsQuery ?: "").generate(src)
        val inc = tags.single { it.isDefinition }
        assertEquals("inc", src.decodeToString(inc.nameRange.first, inc.nameRange.last + 1))
        assertEquals("Adds one.", inc.docs)
    }

    @Test fun queryErrorsAreReported() {
        assertFailsWith<QueryException> { ts.grammar("rust")!!.query("(no_such_node) @x") }
    }

    @Test fun matchPredicatesFilter() {
        val src = "fn a() {} fn b() {}".encodeToByteArray()
        val g = ts.grammar("rust")!!
        g.query("""(function_item name: (identifier) @n (#eq? @n "b"))""").use { q ->
            g.withTree(src) { t -> assertEquals(listOf("b"), q.matches(t.root, src).map { m -> m.nodes("n").single().let { src.decodeToString(it.startByte, it.endByte) } }.toList()) }
        }
    }

    @Test fun bundledTagsQueriesCompile() {
        for (g in ts.grammars) g.tagsQuery?.let { tags -> TagsConfiguration(g, tags, g.localsQuery ?: "") }
    }

    @Test fun rustTagsFindDefinitionsAndCalls() {
        val g = ts.grammar("rust")!!
        val src = "// Adds.\nfn add(a: i32) -> i32 { helper(a) }\nstruct S;".encodeToByteArray()
        val tags = TagsConfiguration(g, g.tagsQuery!!).generate(src)
        val named = tags.map { src.decodeToString(it.nameRange.first, it.nameRange.last + 1) to it.syntaxType }
        assertTrue("add" to "function" in named, "$named")
        assertTrue("helper" to "call" in named, "$named")
        assertTrue("S" to "class" in named, "$named")
        val add = tags.first { src.decodeToString(it.nameRange.first, it.nameRange.last + 1) == "add" }
        assertTrue(add.isDefinition)
        assertEquals(1, add.start.row)
        assertEquals("fn add(a: i32) -> i32 { helper(a) }", src.decodeToString(add.lineRange.first, add.lineRange.last + 1))
    }
}
