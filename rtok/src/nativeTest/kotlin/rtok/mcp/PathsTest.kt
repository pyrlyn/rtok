package rtok.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import rtok.config.Config
import rtok.plugins.read.Roots
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PathsTest {
    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    private fun root() = Roots.cwd()

    @Test fun aFailedRelativePathNamesTheRootItResolvedAgainst() {
        val text = Paths.annotate("read", obj("""{"path":"src/gone.rs"}"""), "No such file or directory: src/gone.rs", false, false)
        assertTrue(text.endsWith("(relative paths resolved against ${root()})"), text)
    }

    @Test fun anEmptySearchNamesTheRootEvenWhenTheCallerIsKnown() {
        assertEquals("no matches (relative paths resolved against ${root()})", Paths.annotate("search", obj("""{"pattern":"x"}"""), "", true, true))
    }

    @Test fun aKnownCallerRootKeepsAGoodAnswerByteExact() {
        assertEquals("1:x", Paths.annotate("read", obj("""{"path":"a.rs"}"""), "1:x", true, true))
    }

    @Test fun aSharedServerSaysItsRootOnEverySuccess() {
        val text = Paths.annotate("read", obj("""{"path":"a.rs"}"""), "1:x", true, false, shared = true)
        assertEquals("1:x\n[relative paths resolved against ${root()}, which may be another session's: this server is shared, pass absolute paths]", text)
    }

    @Test fun absolutePathsAndOtherToolsAreLeftAlone() {
        assertEquals("err", Paths.annotate("read", obj("""{"path":"/tmp/x"}"""), "err", false, false))
        assertEquals("err", Paths.annotate("expand", obj("""{"id":"a"}"""), "err", false, false))
    }

    @Test fun outlineIsLeftAloneWithoutACallerRoot() {
        val args = obj("""{"path":"a.rs"}""")
        assertEquals(args, Paths.anchorOutline("outline", args))
    }

    @Test fun graphToolsAreListedUnderTheGraphPlugin() {
        val graph = Server.listedFor(Config()).filter { it.plugin == "graph" }.map { it.def.name }
        assertEquals(listOf("symbol", "callers", "impact", "outline", "explore", "graph_diff", "graph_export"), graph)
    }
}
