// Port of tests/proxy_batch_results.rs (T385.4, T583): a Batch results download is forwarded as is and, with
// `[proxy.batch] parse_results`, read into one usage row per result line, bad lines skipped; an OpenAI file
// holding results becomes a Batch call; plain downloads, failed fetches and create/poll calls carry no usage;
// replays count each result once.
@file:Suppress("MagicNumber")

package rtok.proxy

import rtok.config.Config
import rtok.store.UsageRow
import rtok.store.countKind
import rtok.store.usageRows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val BR_SESSION = "batch-results"
private const val ANTHROPIC_JSONL =
    """{"custom_id":"a","result":{"type":"succeeded","message":{"model":"claude-sonnet-5-5","usage":{"input_tokens":10,"cache_creation_input_tokens":3,"cache_read_input_tokens":4,"output_tokens":5}}}}""" +
        "\n" + """{"custom_id":"b","result":{"type":"errored","error":{"type":"error","error":{"type":"overloaded_error"}}}}""" +
        "\n" + """{"custom_id":"c","result":{"type":"succeeded","mess""" + "\n" + "not json at all\n" +
        """{"custom_id":"d","result":{"type":"succeeded","message":{"model":"claude-haiku-5","usage":{"input_tokens":1,"output_tokens":2}}}}""" + "\n"
private const val OPENAI_JSONL =
    """{"id":"r1","custom_id":"a","error":null,"response":{"status_code":200,"request_id":"x","body":{"object":"chat.completion","model":"gpt-5","usage":{"prompt_tokens":100,"completion_tokens":7,"total_tokens":107,"prompt_tokens_details":{"cached_tokens":40}}}}}""" +
        "\n" + """{"id":"r2","custom_id":"b","error":null,"response":{"status_code":200,"request_id":"y","body":{"object":"response","model":"gpt-5-mini","usage":{"input_tokens":9,"output_tokens":2}}}}""" +
        "\n" + """{"id":"r3","custom_id":"c","response":null,"error":{"code":"batch_expired","message":"expired"}}""" +
        "\n" + """{"id":"r4","custom_id":"d","error":null,"response":{"status_code":500,"request_id":"z","body":{"error":{}}}}""" + "\n"

private fun serve(
    path: String,
    status: Int,
    body: String,
) = Mock({ it.method == "GET" && it.path == path }, status, listOf("content-type" to "application/x-jsonl"), listOf(body))

private fun fetch(
    url: String,
    path: String,
) = call("GET", "$url$path", "", "x-rtok-session" to BR_SESSION)

private fun ProxyUnderTest.rows(n: Int): List<UsageRow> {
    eventually("$n usage rows") { state.store.usageRows(BR_SESSION).size >= n }
    return state.store.usageRows(BR_SESSION).sortedWith(compareBy({ it.api }, { it.input }))
}

private fun ProxyUnderTest.settle(
    kind: String,
    n: Long = 1,
) {
    eventually("$n $kind rows") { state.store.countKind(kind) >= n }
    platform.posix.usleep(300_000u)
}

private fun legs(r: UsageRow) = Triple(r.api, r.model, listOf(r.input, r.cacheCreate, r.cacheRead, r.output))

private fun Config.parsing(base: String) = proxy { copy(upstream = base, openaiUpstream = base, batch = batch.copy(parseResults = true)) }

class ProxyBatchResultsTest {
    @Test fun anthropicResultsBecomeUsageRowsAndSkipBadLines() {
        FakeUpstream(serve("/v1/messages/batches/msgbatch_1/results", 200, ANTHROPIC_JSONL)).use { up ->
            ProxyUnderTest.start("batch-results-anthropic", { it.parsing(up.url) }).use { p ->
                val r = fetch(p.url, "/v1/messages/batches/msgbatch_1/results")
                assertTrue(ProxyHttp.isSuccess(r.status))
                assertEquals(ANTHROPIC_JSONL, r.text(), "the results stream is forwarded as is")
                p.settle("api_request:batch")
                assertEquals(
                    listOf(Triple("anthropic", "claude-haiku-5", listOf(1L, 0, 0, 2)), Triple("anthropic", "claude-sonnet-5-5", listOf(10L, 3, 4, 5))),
                    p.rows(2).map(::legs),
                )
                assertEquals(1, p.state.store.countKind("api_request:batch"))
            }
        }
    }

    @Test fun openaiFileContentWithResultsBecomesUsageRowsAndABatchCall() {
        FakeUpstream(serve("/v1/files/file-out/content", 200, OPENAI_JSONL)).use { up ->
            ProxyUnderTest.start("batch-results-openai", { it.parsing(up.url) }).use { p ->
                val r = fetch(p.url, "/v1/files/file-out/content")
                assertTrue(ProxyHttp.isSuccess(r.status))
                assertEquals(OPENAI_JSONL, r.text())
                p.settle("api_request:batch")
                assertEquals(
                    listOf(Triple("openai_chat", "gpt-5", listOf(60L, 0, 40, 7)), Triple("openai_responses", "gpt-5-mini", listOf(9L, 0, 0, 2))),
                    p.rows(2).map(::legs),
                )
                assertEquals(1, p.state.store.countKind("api_request:batch"))
                assertEquals(0, p.state.store.countKind("api_request:files"))
            }
        }
    }

    @Test fun anOrdinaryFileDownloadStaysAFilesCallWithoutRows() {
        FakeUpstream(serve("/v1/files/file-doc/content", 200, "just some text\n")).use { up ->
            ProxyUnderTest.start("batch-results-plain-file", { it.parsing(up.url) }).use { p ->
                fetch(p.url, "/v1/files/file-doc/content")
                p.settle("api_request:files")
                assertTrue(
                    p.state.store
                        .usageRows(BR_SESSION)
                        .isEmpty(),
                )
            }
        }
    }

    @Test fun resultsAreNotReadWhenTheFlagIsOffOrTheFetchFailed() {
        FakeUpstream(serve("/v1/messages/batches/ok/results", 200, ANTHROPIC_JSONL), serve("/v1/messages/batches/gone/results", 404, ANTHROPIC_JSONL)).use { up ->
            ProxyUnderTest.start("batch-results-off", { it.proxy { copy(upstream = up.url) } }).use { p ->
                assertEquals(ANTHROPIC_JSONL, fetch(p.url, "/v1/messages/batches/ok/results").text())
                p.settle("api_request:batch")
                assertTrue(
                    p.state.store
                        .usageRows(BR_SESSION)
                        .isEmpty(),
                )
            }
            ProxyUnderTest.start("batch-results-404", { it.parsing(up.url) }).use { p ->
                assertEquals(404, fetch(p.url, "/v1/messages/batches/gone/results").status)
                p.settle("api_request:batch")
                assertTrue(
                    p.state.store
                        .usageRows(BR_SESSION)
                        .isEmpty(),
                )
            }
        }
    }

    @Test fun createAndPollCallsAreTaggedButCarryNoUsage() {
        val m = Mock({ "/v1/messages/batches" in it.path }, chunks = listOf("""{"id":"msgbatch_1","processing_status":"in_progress"}"""))
        FakeUpstream(m).use { up ->
            ProxyUnderTest.start("batch-results-create", { it.parsing(up.url) }).use { p ->
                val s = "x-rtok-session" to BR_SESSION
                assertTrue(ProxyHttp.isSuccess(call("POST", "${p.url}/v1/messages/batches", """{"requests":[]}""", s).status))
                assertTrue(ProxyHttp.isSuccess(call("GET", "${p.url}/v1/messages/batches/msgbatch_1", "", s).status))
                assertTrue(ProxyHttp.isSuccess(call("GET", "${p.url}/v1/messages/batches", "", s).status))
                assertTrue(ProxyHttp.isSuccess(call("POST", "${p.url}/v1/messages/batches/msgbatch_1/cancel", "", s).status))
                p.settle("api_request:batch", 4)
                assertTrue(
                    p.state.store
                        .usageRows(BR_SESSION)
                        .isEmpty(),
                )
            }
        }
    }

    @Test fun downloadingTheSameResultsTwiceCountsThemOnce() {
        val mocks = arrayOf(serve("/v1/messages/batches/msgbatch_1/results", 200, ANTHROPIC_JSONL), serve("/v1/messages/batches/msgbatch_2/results", 200, ANTHROPIC_JSONL), serve("/v1/files/file_1/content", 200, OPENAI_JSONL))
        FakeUpstream(*mocks).use { up ->
            ProxyUnderTest.start("batch-results-replay", { it.parsing(up.url) }).use { p ->
                for (path in listOf("/v1/messages/batches/msgbatch_1/results", "/v1/files/file_1/content")) repeat(2) { fetch(p.url, path) }
                p.settle("api_request:batch", 4)
                assertEquals(4, p.rows(4).size)
                fetch(p.url, "/v1/messages/batches/msgbatch_2/results")
                p.settle("api_request:batch", 5)
                assertEquals(6, p.rows(6).size)
            }
        }
    }
}
