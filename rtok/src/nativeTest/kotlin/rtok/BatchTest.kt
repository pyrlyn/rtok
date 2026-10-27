// Port of src/batch.rs tests.
package rtok

import rtok.http.ktor.KtorHttp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val ANTHROPIC = """{"custom_id":"a","params":{"model":"m","max_tokens":1,"messages":[]}}"""
private const val OPENAI = """{"custom_id":"a","method":"POST","url":"/v1/chat/completions","body":{}}"""

class BatchTest {
    @Test
    fun validationNamesTheLineAndTheMissingKey() {
        assertEquals(2, BatchApi.validate("$ANTHROPIC\n\n$ANTHROPIC\n", BatchProvider.Anthropic).first.size)
        val err = assertFailsWith<BatchException> { BatchApi.validate("$ANTHROPIC\n{\"custom_id\":\"b\"}", BatchProvider.Anthropic) }
        assertEquals("line 2: missing `params`", err.message)
        assertTrue(assertFailsWith<BatchException> { BatchApi.validate("nope", BatchProvider.Anthropic) }.message!!.contains("line 1"))
        assertFailsWith<BatchException> { BatchApi.validate("\n \n", BatchProvider.Openai) }
    }

    @Test
    fun anOpenaiBatchTargetsOneEndpoint() {
        assertEquals("/v1/chat/completions", BatchApi.validate(OPENAI, BatchProvider.Openai).second)
        val other = OPENAI.replace("chat/completions", "responses")
        assertFailsWith<BatchException> { BatchApi.validate("$OPENAI\n$other", BatchProvider.Openai) }
    }

    @Test
    fun idsCannotEscapeTheUrlPath() {
        BatchApi.checkId("msgbatch_01-AB")
        for (bad in listOf("", "a/b", "../x", "a?b", "a b")) assertFailsWith<BatchException>(bad) { BatchApi.checkId(bad) }
        assertEquals(BatchProvider.Anthropic, BatchProvider.ofId("msgbatch_1"))
        assertEquals(BatchProvider.Openai, BatchProvider.ofId("batch_1"))
    }

    @Test
    fun aMissingKeyOrBadUrlIsRefusedBeforeAnyRequest() {
        assertFailsWith<BatchException> { BatchApi("http://127.0.0.1:1", BatchProvider.Openai, " ", KtorHttp.FACTORY) }
        assertFailsWith<BatchException> { BatchApi("ftp://x", BatchProvider.Openai, "k", KtorHttp.FACTORY) }
    }
}
