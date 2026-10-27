// Port of tests/proxy.rs, part 2 (T53, T55.11, T114, T51.1, T51.2, T61.2): `compress` mode pointers old payloads
// byte-identically on every wire, the platform context-edit path stands `archive` down, and `expand` freezes ids.
@file:Suppress("MagicNumber")

package rtok.proxy

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import rtok.Expand
import rtok.plugin.Runtime
import rtok.store.SerdeJson
import rtok.store.countKind
import rtok.store.getArchive
import rtok.store.listMeasurements
import rtok.store.measurementCount
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal const val T53_SESSION = "sess-t53"
private const val T114_SESSION = "sess-t114"
internal const val ARCHIVED = "[archived "

internal fun turnText(t: Int) = (1..400).joinToString("\n") { "t$t line $it: some shell output with words" }

/** Six user turns, each carrying one 400-line tool_result (well above `archive.min_tokens`). */
internal fun t53Request(): String =
    SerdeJson.encode(
        buildJsonObject {
            put("model", T51_MODEL)
            put("max_tokens", 8)
            put("system", "sys")
            putJsonArray("tools") { addJsonObject { put("name", "Bash") } }
            putJsonArray("messages") {
                for (t in 1..6) {
                    addJsonObject {
                        put("role", "user")
                        putJsonArray("content") {
                            addJsonObject {
                                put("type", "tool_result")
                                put("tool_use_id", "tu-$t")
                                put("content", turnText(t))
                            }
                        }
                    }
                    addJsonObject {
                        put("role", "assistant")
                        putJsonArray("content") {
                            addJsonObject {
                                put("type", "tool_use")
                                put("id", "tu-${t + 1}")
                                put("name", "Bash")
                                putJsonObject("input") {}
                            }
                        }
                    }
                }
            }
            putJsonObject("metadata") { put("user_id", T53_SESSION) }
        },
    )

internal fun parsed(text: String): JsonElement = SerdeJson.parse(text)!!

internal fun json(bytes: ByteArray) = parsed(bytes.decodeToString()).jsonObject

internal val JsonElement.str get() = jsonPrimitive.content

internal fun JsonElement.messages(role: String) = jsonObject["messages"]!!.jsonArray.map { it.jsonObject }.filter { it["role"]?.str == role }

internal fun toolResultContents(body: JsonObject) = body.messages("user").map { it["content"]!!.jsonArray[0].jsonObject["content"]!!.str }

private fun t114Texts(
    path: String,
    body: JsonObject,
): List<String> =
    when (path) {
        "/v1/messages" -> {
            toolResultContents(body)
        }

        "/v1/chat/completions" -> {
            body.messages("tool").map { it["content"]!!.str }
        }

        else -> {
            body["input"]!!
                .jsonArray
                .map { it.jsonObject }
                .filter { it["type"]?.str == "function_call_output" }
                .map { it["output"]!!.str }
        }
    }

private fun indexOf(
    hay: ByteArray,
    needle: ByteArray,
): Int = (0..hay.size - needle.size).first { i -> needle.indices.all { hay[i + it] == needle[it] } }

class ProxyCompressTest {
    @Test fun proxyCompressRewritesOldToolResultsIdentically() {
        val m = anthropicBody
        FakeUpstream(m).use { up ->
            t51Server("compress", up, "compress").use { p ->
                repeat(2) {
                    val r = postJson("${p.url}/v1/messages", t53Request())
                    assertEquals(200, r.status)
                    assertContentEquals(proxyFixture("anthropic_messages_body.json"), r.body)
                }
                val sent = p.usage(T53_SESSION, 2).map { p.sent(it).decodeToString() }
                assertEquals(sent[0], sent[1], "same request twice → byte-identical upstream bodies")
                val body = parsed(sent[0]).jsonObject
                val contents = toolResultContents(body)
                assertEquals(6, contents.size)
                assertTrue(contents[0].startsWith(ARCHIVED) && contents[1].startsWith(ARCHIVED))
                for (i in 2 until 6) {
                    assertTrue(contents[i].startsWith("t${i + 1} line 1:"), "turn ${i + 1} untouched")
                    assertFalse("[archived" in contents[i])
                }
                assertEquals("sys", body["system"]!!.str)
                assertEquals("Bash", body["tools"]!!.jsonArray[0].jsonObject["name"]!!.str)
                assertEquals(2, p.state.store.countKind("api_request"))
                assertEquals(2, p.state.store.countKind("plugin_run"))
                assertEquals(4, p.state.store.measurementCount("archive"))
            }
        }
    }

    @Test fun proxyExpandFreezesThePointerForTheNextRequest() {
        FakeUpstream(anthropicBody).use { up ->
            t51Server("expand-freeze", up, "compress").use { p ->
                assertEquals(200, postJson("${p.url}/v1/messages", t53Request()).status)
                val pointer = toolResultContents(json(p.sent(p.usage(T53_SESSION, 1)[0])))[0]
                assertTrue(pointer.startsWith(ARCHIVED), pointer)
                val id = pointer.substringAfter("expand(").substringBefore(')')
                val cx = Runtime.open(p.state.cfg, "expand")
                try {
                    assertContentEquals(turnText(1).encodeToByteArray(), Expand.fetch(cx, id), "expand returns the archived original")
                } finally {
                    cx.store.close()
                }
                assertEquals(200, postJson("${p.url}/v1/messages", t53Request()).status)
                val second = toolResultContents(json(p.sent(p.usage(T53_SESSION, 2)[0])))
                assertTrue(second[0].startsWith("t1 line 1:"), "expanded id goes to upstream whole: ${second[0].take(80)}")
                assertTrue(second[1].startsWith(ARCHIVED), "the un-expanded turn-2 pointer stays")
            }
        }
    }

    @Test fun proxyCompressArchivesSixTurnsOnEachWire() {
        val cases =
            listOf(
                Triple("anthropic", "/v1/messages", "anthropic_messages_6turns.json"),
                Triple("chat", "/v1/chat/completions", "openai_chat_6turns.json"),
                Triple("responses", "/v1/responses", "openai_responses_6turns.json"),
            )
        for ((label, path, fixture) in cases) {
            val answer = mapOf("/v1/messages" to "anthropic_messages_body.json", "/v1/chat/completions" to "openai_chat_body.json").getOrElse(path) { "openai_responses_body.json" }
            FakeUpstream(fixtureMock(path, answer, "application/json")).use { up ->
                val server = if (label == "anthropic") t51Server("t114-$label", up, "compress") else openaiServer("t114-$label", up, "compress")
                server.use { p ->
                    val request = SerdeJson.encode(parsed(proxyFixture(fixture).decodeToString())).encodeToByteArray()
                    repeat(2) {
                        val r = postJson("${p.url}$path", request.decodeToString())
                        assertEquals(200, r.status, label)
                        assertContentEquals(proxyFixture(answer), r.body, label)
                    }
                    val sent = p.usage(T114_SESSION, 2).map { p.sent(it) }
                    assertContentEquals(sent[0], sent[1], "$label byte-identical")
                    val pos = indexOf(sent[0], ARCHIVED.encodeToByteArray())
                    assertContentEquals(request.copyOf(pos), sent[0].copyOf(pos), "$label prefix")
                    val contents = t114Texts(path, json(sent[0]))
                    assertEquals(6, contents.size, label)
                    assertTrue(contents[0].startsWith(ARCHIVED) && contents[1].startsWith(ARCHIVED), label)
                    for (i in 2 until 6) assertTrue(contents[i].startsWith("t${i + 1} line 1:"), "$label turn ${i + 1}")
                    assertEquals(4, p.state.store.measurementCount("archive"), label)
                }
            }
        }
    }

    /** The platform path, armed: `context_management` + the beta header go up, `archive` stands down. */
    @Test fun proxyAnthropicContextEditsArmPlatformPath() {
        val m =
            Mock(
                { it.method == "POST" && it.path == "/v1/messages" && it.header("anthropic-beta")?.contains("context-management-2025-06-27") == true },
                headers = listOf("content-type" to "application/json"),
                bytes = listOf(proxyFixture("anthropic_messages_body.json")),
            )
        FakeUpstream(m).use { up ->
            ProxyUnderTest.start("t512", { it.proxy { copy(upstream = up.url, mode = "compress", contextManagement = true) } }).use { p ->
                val r = postJson("${p.url}/v1/messages", t53Request())
                assertEquals(200, r.status)
                assertContentEquals(proxyFixture("anthropic_messages_body.json"), r.body, "response bytes match the fixture")
                assertEquals(1, m.hits)
                val rows = p.usage(T53_SESSION)
                assertEquals(1, rows.size)
                assertEquals(listOf(10L, 0, 0, 2), listOf(rows[0].input, rows[0].cacheCreate, rows[0].cacheRead, rows[0].output))
                val sent = p.sent(rows[0])
                assertEquals(parsed("""{"edits":[{"type":"clear_tool_uses_20250919"}]}"""), json(sent)["context_management"])
                assertFalse(ARCHIVED in sent.decodeToString(), "archive stands down while the platform clears")
                assertEquals(0, p.state.store.measurementCount("archive"))
                assertEquals(
                    listOf("context_management"),
                    p.state.store
                        .listMeasurements("proxy")
                        .map { it.kind },
                )
            }
        }
    }
}
