@file:Suppress("MagicNumber")

package rtok.proxy

import kotlinx.serialization.json.*
import rtok.config.Estimator
import rtok.config.ToolsRewrite
import rtok.config.envVar
import rtok.estimate
import rtok.plugin.sdk.Class
import rtok.store.SerdeJson
import kotlin.test.*

private val est = Estimator()

private fun cfg(
    max: Int,
    deny: List<String> = emptyList(),
    compact: Boolean = false,
) = ToolsRewrite(enabled = true, compactSchema = compact, maxDescriptionTokens = max.toUInt(), deny = deny)

private val compactCfg = cfg(60, compact = true)

private fun rw(
    body: JsonElement,
    c: ToolsRewrite,
) = ToolsRewriter.rewrite(body, c, est)

private fun catalog() = js(rtok.sys.readToString("${envVar("RTOK_FIXTURES")}/mcp_catalog_github.json")!!)

private fun JsonElement.p(vararg k: Any): JsonElement? = k.fold(this as JsonElement?) { e, key -> if (key is Int) (e as? JsonArray)?.getOrNull(key) else (e as? JsonObject)?.get(key as String) }

/** `description`, `default` and `examples` are the only keys T686 may rewrite. */
private fun withoutProse(v: JsonElement): JsonElement =
    when (v) {
        is JsonObject -> JsonObject(v.filterKeys { it !in setOf("description", "default", "examples") }.mapValues { withoutProse(it.value) })
        is JsonArray -> JsonArray(v.map(::withoutProse))
        else -> v
    }

/** Port of src/proxy/tools_rewrite.rs tests. */
class ToolsRewriteTest {
    private val long = "Short one. This second sentence is far too long to keep under the cap."

    @Test fun anthropicTruncatesDescriptionNotSchema() {
        val schema = js("""{"type":"object","properties":{"command":{"type":"string"}}}""")
        val body = js("""{"tools":[{"name":"Bash","description":"$long","input_schema":$schema}],"messages":[{"role":"user","content":"hi"}]}""")
        val r = rw(body, cfg(5))!!
        assertTrue(r.delta.changed && r.delta.afterBytes < r.delta.beforeBytes)
        assertEquals(JsonPrimitive("Short one."), r.body.p("tools", 0, "description"))
        assertEquals(schema, r.body.p("tools", 0, "input_schema"))
        assertEquals(JsonPrimitive("hi"), r.body.p("messages", 0, "content"))
        assertEquals(r.body, rw(r.body, cfg(5))!!.body)
    }

    @Test fun openaiChatTruncatesFunctionDescriptionNotParameters() {
        val params = js("""{"type":"object","properties":{"q":{"type":"string"}}}""")
        val r = rw(js("""{"tools":[{"type":"function","function":{"name":"search","description":"$long","parameters":$params}}]}"""), cfg(5))!!
        assertTrue(r.delta.changed)
        assertEquals(JsonPrimitive("Short one."), r.body.p("tools", 0, "function", "description"))
        assertEquals(params, r.body.p("tools", 0, "function", "parameters"))
    }

    @Test fun denyDropsToolsButNotALaterCall() {
        val body =
            js(
                """{"tools":[{"name":"keep","description":"Stay.","input_schema":{"type":"object"}},
                {"name":"drop_me","description":"Gone.","input_schema":{"type":"object"}}],
                "messages":[{"role":"assistant","content":[{"type":"tool_use","id":"t1","name":"drop_me","input":{}}]}]}""",
            )
        val r = rw(body, cfg(0, deny = listOf("drop_me")))!!
        assertTrue(r.delta.changed)
        assertEquals(1, (r.body.p("tools") as JsonArray).size)
        assertEquals(JsonPrimitive("keep"), r.body.p("tools", 0, "name"))
        assertEquals(JsonPrimitive("drop_me"), r.body.p("messages", 0, "content", 0, "name"))
    }

    @Test fun offOrMissingToolsIsANoOp() {
        assertNull(rw(js("""{"tools":[{"name":"Bash","description":"Hi there."}]}"""), cfg(5).copy(enabled = false)))
        assertNull(rw(js("""{"messages":[]}"""), cfg(5)))
    }

    /** T686 gate: a realistic catalog shrinks by >= 30 % while names, types, `required` and `enum` stay. */
    @Test fun compactSchemaCutsARealisticCatalogBy30PercentAndKeepsTheShape() {
        val original = catalog()
        val tools = original.p("tools") as JsonArray
        assertTrue(tools.size >= 10)
        val r = rw(original, compactCfg)!!
        assertTrue(r.delta.changed)
        val tok = { b: JsonElement -> estimate(SerdeJson.encode(b.p("tools")!!), Class.Json, est) }
        val (before, after) = tok(original) to tok(r.body)
        val cut = 100.0 * (before - after) / before
        assertTrue(cut >= 30.0, "$before -> $after tokens, cut $cut % < 30 %")
        tools.zip(r.body.p("tools") as JsonArray).forEach { (old, new) ->
            assertEquals(old.p("name"), new.p("name"))
            assertEquals(old.p("input_schema", "required"), new.p("input_schema", "required"))
            assertEquals(withoutProse(old), withoutProse(new), old.p("name").toString())
            (old.p("input_schema", "properties") as JsonObject).forEach { (name, prop) ->
                val now = new.p("input_schema", "properties", name)
                assertEquals(prop.p("type"), now?.p("type"), name)
                assertEquals(prop.p("enum"), now?.p("enum"), name)
            }
        }
        assertEquals(tools, js(r.delta.original!!))
    }

    @Test fun compactSchemaIsByteStableAndIdempotent() {
        val a = rw(catalog(), compactCfg)!!.body
        val b = rw(catalog(), compactCfg)!!.body
        assertEquals(SerdeJson.encode(a), SerdeJson.encode(b))
        val again = rw(b, compactCfg)!!
        assertTrue(!again.delta.changed && again.delta.original == null)
        assertEquals(a, again.body)
    }

    @Test fun compactSchemaOffLeavesInputSchemaAlone() {
        val original = catalog()
        val r = rw(original, cfg(60))!!
        (original.p("tools") as JsonArray).zip(r.body.p("tools") as JsonArray).forEach { (o, n) ->
            assertEquals(o.p("input_schema"), n.p("input_schema"))
        }
    }
}

/** Port of the schema-compaction tests in src/proxy/tools_rewrite.rs. */
class ToolsSchemaCompactTest {
    @Test fun propertyDescriptionsCutToFirstSentenceRecursively() {
        val body =
            js(
                """{"tools":[{"name":"t","description":"Hi.","input_schema":{"type":"object","description":"Root. Second.",
            "properties":{
              "a":{"type":"string","description":"Path, e.g. /tmp/x. Never absolute."},
              "b":{"type":"array","description":"List. More.","items":{"type":"object","description":"Item. More.",
                   "properties":{"c":{"type":"string","description":"Deep. Deeper."}}}},
              "d":{"anyOf":[{"type":"string","description":"One. Two."},{"type":"null"}]},
              "plain":{"type":"string","description":"No terminator at all"}}}}]}""",
            )
        val r = rw(body, compactCfg)!!
        assertTrue(r.delta.changed)
        val s = r.body.p("tools", 0, "input_schema")!!
        assertEquals(JsonPrimitive("Root."), s.p("description"))
        assertEquals(JsonPrimitive("Path, e.g. /tmp/x."), s.p("properties", "a", "description"))
        assertEquals(JsonPrimitive("List."), s.p("properties", "b", "description"))
        assertEquals(JsonPrimitive("Item."), s.p("properties", "b", "items", "description"))
        assertEquals(JsonPrimitive("Deep."), s.p("properties", "b", "items", "properties", "c", "description"))
        assertEquals(JsonPrimitive("One."), s.p("properties", "d", "anyOf", 0, "description"))
        assertEquals(JsonPrimitive("No terminator at all"), s.p("properties", "plain", "description"))
    }

    @Test fun zeroDefaultsAndRestatingExamplesAreDroppedNothingElse() {
        val body =
            js(
                """{"tools":[{"name":"t","description":"Hi.","input_schema":{"type":"object","properties":{
              "flag":{"type":"boolean","default":false},"n":{"type":"integer","default":0},
              "s":{"type":"string","default":""},"l":{"type":"array","default":[]},
              "kept_n":{"type":"integer","default":30},"kept_flag":{"type":"boolean","default":true},
              "no_type":{"default":0},
              "mode":{"type":"string","enum":["a","b"],"default":"a","examples":["a","b"]},
              "echo":{"type":"string","default":"x","examples":["x"]},
              "real":{"type":"string","examples":["org:github"]},
              "mixed":{"type":"string","enum":["a","b"],"examples":["a","zzz"]}}}}]}""",
            )
        val p = rw(body, compactCfg)!!.body.p("tools", 0, "input_schema", "properties")!!
        for (gone in listOf("flag", "n", "s", "l")) assertNull(p.p(gone, "default"), gone)
        assertEquals(JsonPrimitive(30), p.p("kept_n", "default"))
        assertEquals(JsonPrimitive(true), p.p("kept_flag", "default"))
        assertEquals(JsonPrimitive(0), p.p("no_type", "default"))
        assertEquals(JsonPrimitive("a"), p.p("mode", "default"))
        assertNull(p.p("mode", "examples"))
        assertEquals(js("""["a","b"]"""), p.p("mode", "enum"))
        assertEquals(JsonPrimitive("x"), p.p("echo", "default"))
        assertNull(p.p("echo", "examples"))
        assertEquals(js("""["org:github"]"""), p.p("real", "examples"))
        assertEquals(js("""["a","zzz"]"""), p.p("mixed", "examples"))
    }

    @Test fun propertiesNamedLikeKeywordsAreNotRewritten() {
        val schema =
            js(
                """{"type":"object","properties":{"description":{"type":"string"},"default":{"type":"boolean"},
            "examples":{"type":"array","items":{"type":"string"}}},"required":["description"]}""",
            )
        val r = rw(js("""{"tools":[{"name":"t","description":"Hi.","input_schema":$schema}]}"""), compactCfg)!!
        assertTrue(!r.delta.changed && r.delta.original == null)
        assertEquals(schema, r.body.p("tools", 0, "input_schema"))
    }

    @Test fun compactSchemaCoversOpenaiFunctionParameters() {
        val body =
            js(
                """{"tools":[{"type":"function","function":{"name":"f","description":"Hi.","parameters":{"type":"object",
            "properties":{"q":{"type":"string","description":"Query. Extra words.","default":""}}}}}]}""",
            )
        val r = rw(body, compactCfg)!!
        assertTrue(r.delta.changed)
        val q = r.body.p("tools", 0, "function", "parameters", "properties", "q")!!
        assertEquals(JsonPrimitive("Query."), q.p("description"))
        assertNull(q.p("default"))
    }

    /** T59.5 truncation and `deny` lose text too: their original is recoverable as well. */
    @Test fun anyChangeCarriesTheOriginalTools() {
        val body =
            js(
                """{"tools":[{"name":"keep","description":"Short one. This second sentence is far too long to keep under the cap."},
            {"name":"gone","description":"Gone."}]}""",
            )
        val r = rw(body, cfg(5, deny = listOf("gone")))!!
        assertEquals(body.p("tools"), js(r.delta.original!!))
        assertNull(rw(js("""{"tools":[{"name":"k","description":"Hi."}]}"""), cfg(5))!!.delta.original)
    }
}
