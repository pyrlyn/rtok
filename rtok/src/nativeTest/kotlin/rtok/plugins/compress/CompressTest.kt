// Port of src/plugins/compress/mod.rs tests.
package rtok.plugins.compress

import kotlinx.serialization.json.*
import rtok.config.Config
import rtok.plugin.sdk.*
import rtok.proxy.AnthropicWire
import rtok.shortId
import rtok.store.countKind
import rtok.store.putArchiveSummary
import rtok.support.*
import kotlin.test.*
import rtok.plugins.archive.rewrite as archiveRewrite

private fun big(tag: String) = (1..400).joinToString("\n") { "$tag line $it: src/foo.rs: some shell output with words" }

private fun on(
    enabled: Boolean,
    llm: Boolean = false,
): (Config) -> Config =
    { c ->
        compress()(c).let {
            it.copy(
                plugins =
                    it.plugins.copy(
                        compress =
                            it.plugins.compress.copy(
                                enabled = enabled,
                                llm =
                                    it.plugins.compress.llm
                                        .copy(enabled = llm),
                            ),
                    ),
            )
        }
    }

private fun six(vararg tags: String = Array(6) { "t${it + 1}" }) = tags.map(::big)

class CompressTest {
    @Test fun defaultOffLeavesBytesIdentical() =
        withRuntime("compress-off", on(false)) { cx ->
            val content = big("t1")
            val body =
                buildJsonObject {
                    putJsonArray("messages") {
                        (1..6).forEach { i ->
                            addJsonObject {
                                put("role", "user")
                                putJsonArray("content") {
                                    addJsonObject {
                                        put("type", "tool_result")
                                        put("tool_use_id", "tu-$i")
                                        put("content", content)
                                    }
                                }
                            }
                        }
                    }
                }
            val req = WireRequest(AnthropicWire, body)
            archiveRewrite(req.toolResults(), Ctx(cx))
            val afterArchive = req.body
            assertTrue(CompressPlugin.proxyFilter(req, Ctx(cx)).isEmpty())
            assertEquals(afterArchive, req.body)
        }

    @Test fun fixtureCompressesAndExpandRecovers() =
        withRuntime("compress-on", on(true)) { cx ->
            val req = request(six())
            archiveRewrite(req.toolResults(), Ctx(cx))
            val ms = rewrite(req.toolResults(), Ctx(cx))
            assertEquals(2, ms.size)
            assertTrue(ms[0].afterBytes < ms[0].beforeBytes)
            assertTrue(req.values()[0].startsWith("[compress "))
            assertContentEquals(big("t1").encodeToByteArray(), cx.getArchive(ms[0].refId!!))
            assertEquals(0, cx.store.countKind("measurement"))
        }

    @Test fun summaryIsDeterministic() =
        withRuntime("compress-det", on(true)) { cx ->
            fun run() =
                request(six("one", "two", "three", "four", "five", "six"))
                    .also {
                        archiveRewrite(it.toolResults(), Ctx(cx))
                        rewrite(it.toolResults(), Ctx(cx))
                    }.body
            assertEquals(run(), run())
        }

    @Test fun extractiveSummaryShape() {
        val s = extractiveSummary("abc123", "title line\nsrc/a.rs: ok\nerr: fail")
        assertTrue("type: tool_output" in s)
        assertTrue("title: title line" in s)
        assertTrue("expand(abc123)" in s)
    }

    @Test fun summaryPutsErrorsFirstAndClipsEachLineOnce() {
        val long = "src/big.rs: " + "x".repeat(5000)
        val s = extractiveSummary("id", "head\nn1\nn2\nn3\na: 1\nb: 2\nc: 3\nERROR: boom\n$long\n$long\nsrc/z.rs")
        assertTrue(s.split("\nfacts:")[1].startsWith("\n- ERROR: boom"), s)
        assertTrue(s.encodeToByteArray().size < 1000, "long lines must be clipped")
        assertEquals(1, Regex("src/big\\.rs").findAll(s).count(), s)
    }

    @Test fun aStoredSummaryReplacesTheExtractiveViewAndAMissingOneIsOffered() =
        withRuntime("compress-llm", on(true, llm = true)) { cx ->
            val base = request(six())
            archiveRewrite(base.toolResults(), Ctx(cx))
            val id = assertNotNull(cx.archiveDecision("tu-1")).archiveId
            takeOffered()

            fun copy() = WireRequest(ListWire(), base.body)
            val waiting = copy()
            rewrite(waiting.toolResults(), Ctx(cx))
            assertTrue("narrative:" in waiting.values()[0])
            assertTrue(id in takeOffered(), "no summary yet: offered")
            cx.store.putArchiveSummary(id, version(cx.config.plugins.compress.llm.model), "ran 400 lines of shell output")
            val ready = copy()
            rewrite(ready.toolResults(), Ctx(cx))
            val view = ready.values()[0]
            assertTrue("summary: ran 400 lines of shell output" in view, view)
            assertTrue(view.endsWith("expand(${shortId(id)})"), view)
            assertFalse(id in view, view)
            assertFalse(id in takeOffered(), "a summarised id is not offered")
            val again = copy()
            rewrite(again.toolResults(), Ctx(cx))
            assertEquals(ready.body, again.body, "the view is byte-stable")
        }
}
