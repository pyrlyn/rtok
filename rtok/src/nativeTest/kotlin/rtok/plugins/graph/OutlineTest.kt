package rtok.plugins.graph

import kotlin.test.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import rtok.plugin.sdk.Ctx
import rtok.support.tmpDir
import rtok.support.withRuntime
import rtok.sys.*

class OutlineTest {
    @Test fun outlineReusesReadMap() = withRuntime("outline") { rt ->
        val dir = tmpDir("outline")
        try {
            createDirAll("$dir/src"); writeString("$dir/src/main.rs", "fn main() {\n    helper();\n}\nfn helper() {}\n")
            val out = Outline.outlineIn(Ctx(rt), dir, "src/main.rs")
            assertTrue(out.contains("main"), out)
        } finally { removeDirAll(dir) }
    }

    @Test fun ofKindKeepsOneKindAndAWholeFileStaysExact() {
        val map = "pub fn alpha L1\nstruct Beta L4\npub(crate) fn gamma L9"
        assertEquals(map, Outline.ofKind(map, "a.rs", ""))
        assertEquals("pub fn alpha L1\npub(crate) fn gamma L9", Outline.ofKind(map, "a.rs", "fn"))
        assertEquals("no enum in a.rs", Outline.ofKind(map, "a.rs", "enum"))
    }

    @Test fun symbolArgNamesPrefersANonEmptyList() {
        fun o(s: String) = Json.parseToJsonElement(s).jsonObject
        assertEquals(listOf("a", "b"), Outline.symbolArgNames(o("""{"name":"x","names":["a","","b"]}""")))
        assertEquals(listOf("x"), Outline.symbolArgNames(o("""{"name":"x","names":[]}""")))
        assertEquals(listOf(""), Outline.symbolArgNames(o("{}")))
    }

    @Test fun headStalePrefixIsEmptyWithoutAStoredHead() = withRuntime("head-stale") { rt ->
        val dir = tmpDir("head-stale")
        try {
            val cx = Ctx(rt)
            assertEquals("", Outline.headStalePrefix(cx, listOf(dir)))
            cx.pluginStateSet("graph", Index.headKey(dir), """{"head":"deadbeef"}""")
            assertEquals("", Outline.headStalePrefix(cx, listOf(dir)), "not a repo: no live head")
        } finally { removeDirAll(dir) }
    }
}
