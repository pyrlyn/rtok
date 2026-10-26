package rtok.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProtocolTest {
    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test fun memHitLineDropsATitleTheSnippetRepeats() {
        assertEquals("3 use jwt for sessions", Protocol.memHitLine(3, "jwt", "use jwt\n  for sessions"))
        assertEquals("4 auth: tokens expire", Protocol.memHitLine(4, "auth", "tokens expire"))
        assertEquals("5 auth", Protocol.memHitLine(5, "auth", ""))
        assertEquals("6 body only", Protocol.memHitLine(6, "", "body only"))
    }

    @Test fun memHitLineIsOneLineAndKeepsColons() {
        assertEquals("7 a: b: c d", Protocol.memHitLine(7, "a", "b: c\nd"))
    }

    @Test fun initializeNegotiatesProtocolVersion() {
        assertEquals("2024-11-05", Protocol.negotiateProtocolVersion(JsonPrimitive("2024-11-05")))
        assertEquals("2025-06-18", Protocol.negotiateProtocolVersion(JsonPrimitive("1999-01-01")))
        assertEquals("2025-06-18", Protocol.negotiateProtocolVersion(null))
    }

    @Test fun searchAcceptsQueryAndAnExplicitPatternWins() {
        assertEquals(obj("""{"pattern":"x"}"""), Protocol.prepareArgs("search", obj("""{"query":"x"}""")))
        assertEquals(obj("""{"pattern":"p","query":"q"}"""), Protocol.prepareArgs("search", obj("""{"pattern":"p","query":"q"}""")))
        assertEquals(obj("{}"), Protocol.prepareArgs("search", JsonNull))
    }

    @Test fun docsQueryPrepareArgsRenamesQuestionAndUserQuery() {
        assertEquals(obj("""{"query":"a"}"""), Protocol.prepareArgs("docs_query", obj("""{"question":"a"}""")))
        assertEquals(obj("""{"query":"b"}"""), Protocol.prepareArgs("docs_query", obj("""{"userQuery":"b"}""")))
        assertEquals(obj("""{"question":"a"}"""), Protocol.prepareArgs("read", obj("""{"question":"a"}""")))
    }

    @Test fun missingRequiredNamesWhatWasSentAndChecksTypes() {
        val schema = Json.parseToJsonElement("""{"type":"object","properties":{"pattern":{"type":"string"},"n":{"type":"integer"}},"required":["pattern"]}""")
        assertEquals("missing `pattern` (got: q)", Protocol.missingRequired(schema, obj("""{"q":"x"}""")))
        assertEquals("missing `pattern` (got: no arguments)", Protocol.missingRequired(schema, obj("{}")))
        assertEquals("missing `pattern` (got: pattern)", Protocol.missingRequired(schema, obj("""{"pattern":""}""")))
        assertNull(Protocol.missingRequired(schema, obj("""{"pattern":"x"}""")))
    }

    @Test fun anAnyOfNeedsOneSatisfiedBranch() {
        val schema = Json.parseToJsonElement("""{"properties":{"name":{"type":"string"},"id":{"type":"string"}},"anyOf":[{"required":["name"]},{"required":["id"]}]}""")
        assertNull(Protocol.missingRequired(schema, obj("""{"id":"x"}""")))
        assertEquals("missing `name` (got: no arguments)", Protocol.missingRequired(schema, obj("{}")))
    }

    @Test fun capResultCutsAroundAnExpandMarker() {
        assertEquals("short", Tools.capResult("short", "id", 10))
        val out = Tools.capResult("x".repeat(200), "abc", 60)
        assertEquals(true, out.contains("expand(abc)"), out)
    }

    @Test fun fileRootsDecodeToPaths() {
        assertEquals("/a b/c", Roots.filePath("file:///a%20b/c"))
        assertEquals("/x", Roots.filePath("file://localhost/x"))
        assertNull(Roots.filePath("https://x/y"))
        assertNull(Roots.filePath("file://host/x"))
    }
}
