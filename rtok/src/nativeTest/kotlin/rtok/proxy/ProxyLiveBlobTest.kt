// Port of tests/proxy.rs, part 2b (T51.1, T61.2): payloads outside tool results (live JSON blobs, skill bodies) are
// archived the same byte-stable way, each recoverable by its measurement's `ref_id`.
@file:Suppress("MagicNumber")

package rtok.proxy

import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import rtok.store.SerdeJson
import rtok.store.getArchive
import rtok.store.listMeasurements
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val T511_SESSION = "sess-t511"
private const val T612_SESSION = "sess-t612"

/** A 400-row JSON dump: a blob candidate far above `archive.min_tokens`, identical bytes on every re-send. */
private fun t511Blob(turn: Int): String =
    SerdeJson.encode(
        buildJsonObject {
            put(
                "rows",
                buildJsonArray {
                    for (i in 1..400) {
                        addJsonObject {
                            put("id", i)
                            put("turn", turn)
                            put("name", "entry-$i")
                            put("note", "some payload the model already read")
                        }
                    }
                },
            )
        },
    )

private fun t511Request(openai: Boolean): String {
    val messages =
        buildJsonArray {
            for (t in 1..6) {
                val blob = t511Blob(t)
                addJsonObject {
                    put("role", "user")
                    if (openai) {
                        put("content", blob)
                    } else {
                        putJsonArray("content") {
                            addJsonObject {
                                put("type", "text")
                                put("text", blob)
                            }
                        }
                    }
                }
                addJsonObject {
                    put("role", "assistant")
                    put("content", "ok")
                }
            }
        }
    val body =
        if (openai) {
            buildJsonObject {
                put("model", "gpt-4o")
                put("user", T511_SESSION)
                put("messages", messages)
            }
        } else {
            buildJsonObject {
                put("model", T51_MODEL)
                put("max_tokens", 8)
                put("messages", messages)
                putJsonObject("metadata") { put("user_id", T511_SESSION) }
            }
        }
    return SerdeJson.encode(body)
}

private fun t612SkillBody() = "Base directory for this skill: /s/pixel\n\n# Pixel\n" + (1..400).joinToString("\n") { "pixel line $it: widget docs and examples for the skill body" }

private fun textBlock(text: String) =
    buildJsonArray {
        addJsonObject {
            put("type", "text")
            put("text", text)
        }
    }

private fun t612Request(): String =
    SerdeJson.encode(
        buildJsonObject {
            put("model", T51_MODEL)
            put("max_tokens", 8)
            putJsonObject("metadata") { put("user_id", T612_SESSION) }
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        addJsonObject {
                            put("type", "tool_result")
                            put("tool_use_id", "toolu_skill")
                            put("content", "Launching skill: pixel")
                        }
                        addJsonObject {
                            put("type", "text")
                            put("text", t612SkillBody())
                        }
                    }
                }
                for (text in listOf("mid", "now")) {
                    addJsonObject {
                        put("role", "assistant")
                        put("content", "ok")
                    }
                    addJsonObject {
                        put("role", "user")
                        put("content", textBlock(text))
                    }
                }
            }
        },
    )

/** T51.1 live blobs and T61.2 skill bodies: payloads outside tool results, archived the same byte-stable way. */
class ProxyLiveBlobTest {
    @Test fun proxyCompressShrinksLiveBlobsOnBothWires() {
        for ((label, path, openai) in listOf(Triple("anthropic", "/v1/messages", false), Triple("chat", "/v1/chat/completions", true))) {
            val mock = if (openai) fixtureMock(path, "openai_chat_body.json", "application/json") else anthropicBody
            FakeUpstream(mock).use { up ->
                val tune = { c: rtok.config.Config ->
                    val base = c.copy(plugins = c.plugins.copy(archive = c.plugins.archive.copy(liveBlobs = true)))
                    if (openai) base.proxy { copy(mode = "compress", upstream = "http://127.0.0.1:1", openaiUpstream = up.url) } else base.proxy { copy(mode = "compress", upstream = up.url) }
                }
                ProxyUnderTest.start("t511-$label", tune).use { p ->
                    val request = t511Request(openai)
                    repeat(2) { assertEquals(200, postJson("${p.url}$path", request).status, label) }
                    val sent = p.usage(T511_SESSION, 2).map { p.sent(it) }
                    assertContentEquals(sent[0], sent[1], "$label: replay is byte-identical upstream")
                    assertTrue(sent[0].size < request.length / 2, "$label: the blobs actually left the request")
                    val texts =
                        json(sent[0]).messages("user").map {
                            val c = it["content"]!!
                            if (openai) c.str else c.jsonArray[0].jsonObject["text"]!!.str
                        }
                    assertEquals(6, texts.size, label)
                    texts.forEachIndexed { i, text ->
                        if (i < 4) assertTrue(text.startsWith(ARCHIVED), "$label: turn ${5 - i} pointered") else assertEquals(t511Blob(i + 1), text, "$label: working edge stays whole")
                    }
                    val ms = p.state.store.listMeasurements("archive")
                    assertTrue(ms.all { it.kind == "live_blob" }, "$label: nothing but blobs is measured")
                    assertEquals(8, ms.size, "$label: four blobs, measured on both requests")
                    val recovered =
                        ms
                            .map { it.refId!! }
                            .distinct()
                            .map {
                                p.state.store
                                    .getArchive(it, null)!!
                                    .decodeToString()
                            }.sorted()
                    assertEquals((1..4).map { t511Blob(it) }.sorted(), recovered, "$label: every pointer expands to its original blob")
                }
            }
        }
    }

    @Test fun proxyCompressArchivesSkillBodiesOutsideKeepTurns() {
        FakeUpstream(anthropicBody).use { up ->
            val tune = { c: rtok.config.Config ->
                c.copy(plugins = c.plugins.copy(archive = c.plugins.archive.copy(keepTurns = 2u, skills = true))).proxy { copy(mode = "compress", upstream = up.url) }
            }
            ProxyUnderTest.start("t612-skill", tune).use { p ->
                repeat(2) { assertEquals(200, postJson("${p.url}/v1/messages", t612Request()).status) }
                val sent = p.usage(T612_SESSION, 2).map { p.sent(it) }
                assertContentEquals(sent[0], sent[1], "replay is byte-identical")
                val msgs = json(sent[0])["messages"]!!.jsonArray
                val first = msgs[0].jsonObject["content"]!!.jsonArray
                val pointer = first[1].jsonObject["text"]!!.str
                assertTrue(pointer.startsWith(ARCHIVED) && "skill pixel" in pointer && "expand(" in pointer, pointer.take(120))
                assertEquals("Launching skill: pixel", first[0].jsonObject["content"]!!.str, "the Launching skill result stays")
                assertEquals(
                    "mid",
                    msgs[2]
                        .jsonObject["content"]!!
                        .jsonArray[0]
                        .jsonObject["text"]!!
                        .str,
                    "turns inside keep_turns stay whole",
                )
                val ms = p.state.store.listMeasurements("archive")
                assertTrue(ms.all { it.kind == "skill" }, ms.toString())
                assertEquals(2, ms.size, "one measurement per request, not a re-archive")
                assertEquals(ms[0].refId, ms[1].refId)
                assertEquals(
                    t612SkillBody(),
                    p.state.store
                        .getArchive(ms[0].refId!!, null)!!
                        .decodeToString(),
                )
            }
        }
    }
}
