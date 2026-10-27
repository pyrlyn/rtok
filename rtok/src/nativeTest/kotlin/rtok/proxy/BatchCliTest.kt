// Port of tests/batch_cli.rs (T385.12.1): `rtok batch` through a real proxy against fake providers. Each
// provider's calls reach its own upstream, the JSONL goes out unchanged, and the ledger tags every hop as a
// Batch-lane call. No real provider is contacted.
@file:Suppress("MagicNumber")

package rtok.proxy

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import rtok.BatchApi
import rtok.BatchException
import rtok.BatchProvider
import rtok.http.ktor.KtorHttp
import rtok.store.SerdeJson
import rtok.store.countKind
import rtok.support.tmpDir
import rtok.sys.exists
import rtok.sys.readToString
import rtok.sys.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val ANTHROPIC_LINES =
    """{"custom_id":"a","params":{"model":"claude-haiku-4-5","max_tokens":1,"messages":[]}}""" + "\n" +
        """{"custom_id":"b","params":{"model":"claude-haiku-4-5","max_tokens":2,"messages":[]}}""" + "\n"
private const val OPENAI_LINES = """{"custom_id":"a","method":"POST","url":"/v1/chat/completions","body":{"model":"gpt-5"}}""" + "\n"
private const val RESULTS = "{\"custom_id\":\"a\",\"result\":{}}\n"

private fun at(
    method: String,
    path: String,
    status: Int = 200,
    body: String,
    also: (Seen) -> Boolean = { true },
) = Mock({ it.method == method && it.path == path && also(it) }, status, chunks = listOf(body))

class BatchCliTest {
    @Test fun anAnthropicBatchIsCreatedPolledAndFetchedThroughTheProxy() {
        val create =
            at("POST", "/v1/messages/batches", body = """{"id":"msgbatch_1"}""") {
                it.header("x-api-key") == "k" && it.header("anthropic-version") == "2023-06-01" &&
                    it.body == "{\"requests\":[${ANTHROPIC_LINES.trimEnd().replace("\n", ",")}]}"
            }
        val up = FakeUpstream(create, at("GET", "/v1/messages/batches/msgbatch_1", body = """{"processing_status":"ended"}"""), at("GET", "/v1/messages/batches/msgbatch_1/results", body = RESULTS))
        up.use {
            ProxyUnderTest.start("batch-cli-anthropic", { it.proxy { copy(upstream = up.url) } }).use { p ->
                val api = BatchApi(p.url, BatchProvider.Anthropic, "k", KtorHttp.FACTORY)
                val dir = tmpDir("batch-cli")
                writeString("$dir/anthropic.jsonl", ANTHROPIC_LINES)
                assertEquals("""{"id":"msgbatch_1"}""", api.submit("$dir/anthropic.jsonl"))
                assertEquals(1, create.hits)
                assertTrue("ended" in api.status("msgbatch_1"))
                val out = "$dir/anthropic-results.jsonl"
                assertEquals(RESULTS.length.toLong(), api.fetch("msgbatch_1", out))
                assertEquals(RESULTS, readToString(out))
                // The results file is never overwritten, and an id cannot leave the URL path.
                assertFailsWith<BatchException> { api.fetch("msgbatch_1", out) }
                assertFailsWith<BatchException> { api.status("../x") }
                eventually("three batch-lane calls") { p.state.store.countKind("api_request:batch") >= 3 }
            }
        }
    }

    @Test fun anOpenaiBatchReachesTheOpenaiUpstreamNotTheAnthropicOne() {
        val wrong = Mock({ true }, 500)
        val upload =
            at("POST", "/v1/files", body = """{"id":"file-in"}""") {
                it.header("authorization") == "Bearer k" && "name=\"purpose\"\r\n\r\nbatch" in it.body && OPENAI_LINES.trimEnd() in it.body
            }
        val want =
            buildJsonObject {
                put("input_file_id", "file-in")
                put("endpoint", "/v1/chat/completions")
                put("completion_window", "24h")
            }
        val create = at("POST", "/v1/batches", body = """{"id":"batch_1"}""") { SerdeJson.parse(it.body) == want }
        val pending = at("GET", "/v1/batches/batch_0", body = """{"status":"in_progress","output_file_id":null}""")
        val done = at("GET", "/v1/batches/batch_1", body = """{"status":"completed","output_file_id":"file-out"}""")
        val content = at("GET", "/v1/files/file-out/content", body = RESULTS)
        FakeUpstream(wrong).use { anthropic ->
            FakeUpstream(upload, create, pending, done, content).use { openai ->
                ProxyUnderTest.start("batch-cli-openai", { it.proxy { copy(upstream = anthropic.url, openaiUpstream = openai.url) } }).use { p ->
                    val api = BatchApi("${p.url}/", BatchProvider.Openai, "k", KtorHttp.FACTORY)
                    val dir = tmpDir("batch-cli-openai")
                    writeString("$dir/openai.jsonl", OPENAI_LINES)
                    assertEquals("""{"id":"batch_1"}""", api.submit("$dir/openai.jsonl"))
                    assertEquals(1 to 1, upload.hits to create.hits)
                    api.fetch("batch_1", "$dir/openai-results.jsonl")
                    assertEquals(RESULTS, readToString("$dir/openai-results.jsonl"))
                    // A batch without an output file says where it stands and leaves nothing behind.
                    val err = assertFailsWith<BatchException> { api.fetch("batch_0", "$dir/openai-missing.jsonl") }
                    assertTrue("in_progress" in err.message.orEmpty(), err.message)
                    assertFalse(exists("$dir/openai-missing.jsonl"))
                    assertEquals(1, pending.hits)
                    assertEquals(0, wrong.hits, "an OpenAI call went to the Anthropic upstream")
                    eventually("two files-lane calls") { p.state.store.countKind("api_request:files") >= 2 }
                }
            }
        }
    }

    @Test fun aProviderErrorIsReportedWithItsStatus() {
        FakeUpstream(at("GET", "/v1/messages/batches/msgbatch_9", 404, """{"error":"not_found"}""")).use { up ->
            ProxyUnderTest.start("batch-cli-404", { it.proxy { copy(upstream = up.url) } }).use { p ->
                val api = BatchApi(p.url, BatchProvider.Anthropic, "k", KtorHttp.FACTORY)
                val err = assertFailsWith<BatchException> { api.status("msgbatch_9") }.message.orEmpty()
                assertTrue(err.startsWith("404 Not Found") && "not_found" in err, err)
            }
        }
    }
}
