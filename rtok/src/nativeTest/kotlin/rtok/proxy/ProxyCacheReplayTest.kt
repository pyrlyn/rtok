// Port of tests/proxy_cache_replay.rs: nine agent turns through `compress` mode with tools rewrite, toon, archive
// and compress on. Everything before the live zone, and every top-level field, is byte-identical from one turn
// to the next, so the provider's prompt cache keeps hitting; the same holds with context management armed.
@file:Suppress("MagicNumber")

package rtok.proxy

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import rtok.config.Config
import rtok.store.SerdeJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private const val SESSION = "sess-cache-replay"
private const val KEEP_TURNS = 3
private const val TURNS = 9
private const val LONG_DESCRIPTION =
    "Runs a shell command and returns its output. It accepts a working directory, a timeout and an environment, streams long " +
        "output back in chunks, and reports the exit status separately. Prefer it over ad hoc scripts, and keep each command short " +
        "and idempotent whenever possible."

private fun toolOutput(t: Int): String =
    if (t % 3 == 0) {
        SerdeJson.encode(
            buildJsonArray {
                for (i in 1..80) {
                    add(
                        buildJsonObject {
                            put("id", i)
                            put("name", "item-$t-$i")
                            put("state", "ok")
                        },
                    )
                }
            },
        )
    } else {
        (1..300).joinToString("\n") { i -> if (t % 2 == 0) "\u001b[32mt$t line $i\u001b[0m: some shell output with words" else "t$t line $i: some shell output with words" }
    }

private fun replayRequest(n: Int): String {
    val messages =
        buildJsonArray {
            add(
                buildJsonObject {
                    put("role", "user")
                    put("content", "start the task")
                },
            )
            for (t in 1..n) {
                add(SerdeJson.parse("""{"role":"assistant","content":[{"type":"tool_use","id":"tu-$t","name":"Bash","input":{}}]}""")!!)
                add(
                    buildJsonObject {
                        put("role", "user")
                        put(
                            "content",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("type", "tool_result")
                                        put("tool_use_id", "tu-$t")
                                        put("content", toolOutput(t))
                                    },
                                )
                            },
                        )
                    },
                )
            }
        }
    val tools = SerdeJson.parse("""[{"name":"Bash","description":${SerdeJson.encode(JsonPrimitive(LONG_DESCRIPTION))},"input_schema":{"type":"object"}}]""")!!
    return SerdeJson.encode(
        JsonObject(
            mapOf(
                "model" to JsonPrimitive("claude-test"),
                "max_tokens" to JsonPrimitive(8),
                "system" to JsonPrimitive("You are a coding agent."),
                "tools" to tools,
                "messages" to messages,
                "metadata" to buildJsonObject { put("user_id", SESSION) },
            ),
        ),
    )
}

private fun configure(
    c: Config,
    base: String,
    contextManagement: Boolean,
): Config {
    val p = c.proxy { copy(upstream = base, openaiUpstream = base, geminiUpstream = base, mode = "compress", toolsRewrite = toolsRewrite.copy(enabled = true), contextManagement = contextManagement) }
    val pl = p.plugins
    return p.copy(plugins = pl.copy(toon = pl.toon.copy(enabled = true), compress = pl.compress.copy(enabled = true), archive = pl.archive.copy(keepTurns = KEEP_TURNS.toUInt())))
}

private fun liveEdge(messages: JsonArray): Int =
    messages
        .withIndex()
        .reversed()
        .filter { (it.value as JsonObject)["role"] == JsonPrimitive("user") }
        .getOrNull(KEEP_TURNS - 1)
        ?.index ?: 0

private fun replay(
    tag: String,
    contextManagement: Boolean,
): List<JsonObject> {
    val sink = Mock({ true }, headers = listOf("content-type" to "application/json"), chunks = listOf("{}"))
    return FakeUpstream(sink).use { up ->
        ProxyUnderTest.start(tag, { configure(it, up.url, contextManagement) }).use { p ->
            for (n in 1..TURNS) {
                val s = call("POST", "${p.url}/v1/messages", replayRequest(n), "content-type" to "application/json").status
                assertTrue(s in 200..299, "turn $n: $s")
            }
        }
        assertEquals(TURNS, up.seen.size, "one upstream request per turn")
        up.seen.map { SerdeJson.parse(it.body) as JsonObject }
    }
}

private fun bytes(v: JsonElement) = SerdeJson.encode(v)

private fun assertPrefixIsStable(sent: List<JsonObject>) {
    for ((n, pair) in sent.windowed(2).withIndex()) {
        val (prev, next) = pair
        val turn = n + 2
        assertEquals(bytes(JsonObject(prev + ("messages" to JsonNull))), bytes(JsonObject(next + ("messages" to JsonNull))), "turn $turn: tools/system/top-level fields moved")
        val pm = prev["messages"] as JsonArray
        val nm = next["messages"] as JsonArray
        for (i in 0 until liveEdge(pm)) assertEquals(bytes(pm[i]), bytes(nm[i]), "turn $turn: message $i changed after it left the live zone")
    }
}

private fun textOf(message: JsonElement): String {
    val c = (((message as JsonObject)["content"] as JsonArray)[0] as JsonObject)["content"]!!
    return (c as? JsonPrimitive)?.takeIf { it.isString }?.content ?: SerdeJson.encode(c)
}

class ProxyCacheReplayTest {
    @Test fun agentPrefixStaysByteStableAcrossTurnsWithRewritesOn() {
        val sent = replay("cache-replay-archive", false)
        assertPrefixIsStable(sent)
        val last = sent[TURNS - 1]
        val messages = last["messages"] as JsonArray
        val oldest = textOf(messages[2])
        assertTrue(oldest.length < toolOutput(1).length / 4, "oldest: ${oldest.take(300)}")
        assertEquals(toolOutput(TURNS), textOf(messages.last()), "newest result must stay whole")
        val original = SerdeJson.parse(replayRequest(TURNS)) as JsonObject
        assertNotEquals(original["messages"], last["messages"])
        val desc = ((last["tools"] as JsonArray)[0] as JsonObject)["description"] as JsonPrimitive
        assertTrue(desc.content.length < LONG_DESCRIPTION.length)
    }

    @Test fun agentPrefixStaysByteStableWithContextManagementArmed() {
        val sent = replay("cache-replay-context", true)
        assertPrefixIsStable(sent)
        assertTrue(sent[TURNS - 1]["context_management"] is JsonObject, "context management was not armed")
    }
}
