package rtok.mcp.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// Port of the push_item / pull_items tests in src/agents/jsonc.rs.
class JsoncItemsTest {
    private fun j(s: String) = Json.parseToJsonElement(s)

    private fun isOurs(v: JsonElement) =
        (v as? JsonPrimitive)?.content == "ours" && (v as JsonPrimitive).isString ||
            ((v as? JsonObject)?.get("c") as? JsonPrimitive)?.content == "ours"

    private fun push(
        raw: String,
        keys: List<String>,
        item: JsonElement,
    ) = JsoncItems.pushItem(raw, "t", keys, item)

    private fun pull(
        raw: String,
        keys: List<String>,
    ) = JsoncItems.pullItems(raw, "t", keys, ::isOurs)

    @Test fun pushItemFollowsTheListStyleAndPullItemsUndoesIt() {
        val ours = JsonPrimitive("ours")
        val hook = j("""{"c": "ours"}""")
        val cases =
            listOf(
                Triple("{\n  // c\n  \"a\": [\"x\", \"y\"],\n  \"b\": 1\n}\n", listOf("a"), ours) to true,
                Triple("{\n  \"a\": [\n    \"x\",\n    \"y\",\n  ]\n}\n", listOf("a"), ours) to true,
                Triple("{\n  \"h\": {\n    \"o\": [1]\n  }\n}\n", listOf("h", "p"), hook) to true,
                Triple("{\n  \"h\": {\n    \"p\": [\n      {\"c\": \"x\"}\n    ]\n  }\n}\n", listOf("h", "p"), hook) to true,
                Triple("{\n  // only a comment\n}\n", listOf("h", "p"), hook) to false,
                Triple("{\"z\": 1}", listOf("h", "p"), hook) to false,
            )
        for ((case, exact) in cases) {
            val (raw, keys, item) = case
            val got = push(raw, keys, item)
            val list = keys.fold(Jsonc.parse(got)) { v, k -> (v as JsonObject).getValue(k) } as JsonArray
            assertTrue(list.any(::isOurs), got)
            val (back, n) = pull(got, keys)
            assertEquals(1, n, got)
            if (exact) assertEquals(raw, back, got) else assertEquals(Jsonc.parse(raw), Jsonc.parse(back), "$raw -> $got -> $back")
        }
    }

    @Test fun pushItemCreatesABlankFileAndRefusesAValueOfAnotherShape() {
        assertEquals(j("""{"a": ["ours"]}"""), Jsonc.parse(push("{\"a\": []}", listOf("a"), JsonPrimitive("ours"))))
        val got = push("", listOf("h", "p"), JsonPrimitive("ours"))
        assertEquals(j("""{"h": {"p": ["ours"]}}"""), Jsonc.parse(got))
        val (back, n) = pull(got, listOf("h", "p"))
        assertEquals(1, n)
        assertTrue(JsoncItems.isEmptyAt(back, emptyList()), back)
        for (raw in listOf("{\"h\": 1}", "{\"h\": {\"p\": {}}}", "[1]")) {
            assertFailsWith<McpConfigException>(raw) { push(raw, listOf("h", "p"), JsonPrimitive("x")) }
        }
    }

    @Test fun removingArrayElementsWithTrailingCommentsKeepsTheTextValid() {
        for ((raw, want) in listOf(
            """{"a":["ours" /*keep*/,"x"]}""" to """{"a":[ /*keep*/"x"]}""",
            """{"a":["ours" /*1*/,"ours" /*2*/,"x"]}""" to """{"a":[ /*1*/ /*2*/"x"]}""",
            """{"a":["x","ours" /*k*/]}""" to """{"a":["x" /*k*/]}""",
        )) {
            val (got, n) = pull(raw, listOf("a"))
            assertEquals(want to true, got to (n > 0), raw)
            assertEquals(j("""["x"]"""), (Jsonc.parse(got) as JsonObject)["a"], got)
        }
    }

    @Test fun pullItemsLeavesAListThatHoldsNothingOfOurs() {
        val raw = "{\"a\": [\"x\"]}"
        assertEquals(raw to 0, pull(raw, listOf("a")))
        assertEquals(raw to 0, pull(raw, listOf("a", "b")))
    }
}
