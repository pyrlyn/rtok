@file:Suppress("MagicNumber")

package rtok.proxy

import rtok.proxy.BatchResults.ResultUsage
import rtok.proxy.BatchResults.Source
import rtok.store.*
import kotlin.test.*

private fun jsonl(vararg lines: String) = lines.joinToString("\n").encodeToByteArray()

private fun anthropicLine(
    id: String?,
    input: Long,
): String {
    val idPart = id?.let { "\"custom_id\":\"$it\"," }.orEmpty()
    return """{$idPart"result":{"type":"succeeded","message":{"model":"m","usage":{"input_tokens":$input,"output_tokens":1}}}}"""
}

private fun freshStore() =
    rtok.support
        .installDb()
        .let { Store.openInMemory() }
        .also { it.upsertSession("s", 1, null, null, "proxy") }

private fun totals(store: Store) = store.usageRows("s").let { r -> r.size to r.sumOf { it.input } }

/** Port of src/proxy/batch_results.rs tests. */
class BatchResultsTest {
    @Test fun anthropicSucceededLinesBecomeRowsAndTheRestDoNot() {
        val body =
            jsonl(
                """{"custom_id":"a","result":{"type":"succeeded","message":{"model":"claude-sonnet-5-5","usage":{"input_tokens":10,"cache_creation_input_tokens":3,"cache_read_input_tokens":4,"output_tokens":5}}}}""",
                """{"custom_id":"b","result":{"type":"errored","error":{"type":"error"}}}""",
                """{"custom_id":"c","result":{"type":"expired"}}""",
                """{"custom_id":"d","result":{"type":"canceled"}}""",
            )
        assertEquals(listOf(ResultUsage("claude-sonnet-5-5", "anthropic", Usage(10, 3, 4, 5), "a")), BatchResults.parse(body))
    }

    @Test fun openaiLinesPickTheirWireFromTheBodyAndSkipFailures() {
        val body =
            jsonl(
                """{"id":"r1","custom_id":"a","error":null,"response":{"status_code":200,"body":{"object":"chat.completion","model":"gpt-5","usage":{"prompt_tokens":100,"completion_tokens":7,"prompt_tokens_details":{"cached_tokens":40}}}}}""",
                """{"id":"r2","custom_id":"b","error":null,"response":{"status_code":200,"body":{"object":"response","model":"gpt-5","usage":{"input_tokens":9,"output_tokens":2}}}}""",
                """{"id":"r3","custom_id":"c","error":null,"response":{"status_code":429,"body":{"usage":{"prompt_tokens":1,"completion_tokens":1}}}}""",
                """{"id":"r4","custom_id":"d","error":{"code":"batch_expired"},"response":null}""",
            )
        val got = BatchResults.parse(body).map { listOf(it.api, it.usage.input, it.usage.cacheRead, it.usage.output) }
        assertEquals(listOf(listOf("openai_chat", 60L, 40L, 7L), listOf("openai_responses", 9L, 0L, 2L)), got)
    }

    /** T583: a result is recorded once per location; another batch's same ids are other requests. */
    @Test fun aResultIsRecordedOncePerLocation() {
        val store = freshStore()

        fun rec(
            scope: String,
            body: ByteArray,
        ): Int {
            val call = store.insertCall("s", "proxy", "api_request:batch", null, null, null, null, null)
            return BatchResults.record(store, "s", call, null, Source.Batch, scope, body)
        }
        val (a, b) = anthropicLine("a", 100) to anthropicLine("b", 7)
        val first = jsonl(a)
        val whole = jsonl(a, b)
        assertEquals(1, rec("https://up/batches/1/results", first))
        assertEquals(1 to 100L, totals(store))
        assertEquals(0, rec("https://up/batches/1/results", first))
        assertEquals(1, rec("https://up/batches/1/results", whole))
        assertEquals(0, rec("https://up/batches/1/results", whole))
        assertEquals(2 to 107L, totals(store))
        assertEquals(2, rec("https://up/batches/2/results", whole))
        assertEquals(4 to 214L, totals(store))
        val anonymous = jsonl(anthropicLine(null, 3))
        assertEquals(1, rec("https://up/batches/3/results", anonymous))
        assertEquals(1, rec("https://up/batches/3/results", anonymous))
        assertEquals(6 to 220L, totals(store))
    }

    @Test fun openaiResultsAreKeyedByTheRequestLineId() {
        val store = freshStore()

        fun line(id: String) = """{"id":"$id","custom_id":"x","error":null,"response":{"status_code":200,"body":{"object":"chat.completion","model":"gpt-5","usage":{"prompt_tokens":10,"completion_tokens":2}}}}"""
        var seen = 0
        repeat(2) {
            val call = store.insertCall("s", "proxy", "api_request:files", null, null, null, null, null)
            seen += BatchResults.record(store, "s", call, null, Source.File, "https://up/files/f/content", jsonl(line("batch_req_1"), line("batch_req_2")))
        }
        assertEquals(2, seen, "two lines sharing a custom_id are still two requests")
        assertEquals(2 to 20L, totals(store))
    }

    @Test fun aMalformedOrTruncatedLineCostsOnlyItself() {
        val ok = """{"custom_id":"a","result":{"type":"succeeded","message":{"model":"m","usage":{"input_tokens":1,"output_tokens":2}}}}"""
        val body = "not json\n$ok\n\u0000\n{\"custom_id\":\"z\",\"res".encodeToByteArray() + byteArrayOf(10, 0xff.toByte(), 0xfe.toByte(), 10) + ok.encodeToByteArray()
        assertEquals(2, BatchResults.parse(body).size)
        assertTrue(BatchResults.parse(ByteArray(0)).isEmpty())
        assertTrue(BatchResults.parse("{}\n[]\n42\n".encodeToByteArray()).isEmpty())
    }

    @Test fun onlyGetsOfResultsOrContentAreRead() {
        assertEquals(Source.Batch, BatchResults.source(Lane.Batch, "GET", "/v1/messages/batches/b/results"))
        assertEquals(Source.File, BatchResults.source(Lane.Files, "GET", "/v1/files/f/content"))
        assertNull(BatchResults.source(Lane.Batch, "POST", "/v1/messages/batches/b/results"))
        assertNull(BatchResults.source(Lane.Agent, "GET", "/v1/files/f/content"))
    }
}
