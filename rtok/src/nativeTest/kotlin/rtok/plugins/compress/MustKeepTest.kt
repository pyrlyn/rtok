// Port of src/plugins/compress/must_keep.rs and the transport-free llm.rs tests.
package rtok.plugins.compress

import kotlin.test.*
import rtok.sys.scalarList

private const val LOG = "Compiling a\nerror[E0308]: mismatched types\n  --> crates/x/src/lib.rs:42:7\n" +
    "connect failed: Connection refused (os error 61) addr=10.0.0.1:5432\n" +
    "ran `RetryBudget` in 248 ms; run `acme settle --retry bat_01HZX4M8Q2`\n" +
    "id 9c1e5ab07f2d44e8 settled=2 MAX_BACKOFF_MS\n\$ cargo test --lib\n41 passed; 2 failed"

class MustKeepTest {
    private fun of(kind: String) = spans(LOG).filter { it.kind == kind }.map { it.text }

    @Test fun everyKindIsFoundOnceInOrder() {
        assertEquals("error[E0308]: mismatched types", of("error")[0])
        assertTrue("E0308" in of("error"))
        assertTrue("crates/x/src/lib.rs:42:7" in of("path"))
        assertTrue("`RetryBudget`" in of("identifier")); assertTrue("MAX_BACKOFF_MS" in of("identifier"))
        listOf("248 ms", "settled=2", "2 failed").forEach { assertTrue(it in of("number"), it) }
        assertTrue("`acme settle --retry bat_01HZX4M8Q2`" in of("command")); assertTrue("cargo test --lib" in of("command"))
        assertTrue("9c1e5ab07f2d44e8" in of("id")); assertTrue("10.0.0.1:5432" in of("id"))
        val all = spans(LOG)
        assertEquals(all.size, all.map { it.text }.toSet().size, "a span is listed once")
        assertTrue(all.all { it.kind in KINDS })
    }

    @Test fun aSummaryThatDropsASpanIsNamed() {
        val all = spans(LOG)
        assertTrue(dropped(all, LOG).isEmpty())
        val gone = dropped(all, LOG.replace("crates/x/src/lib.rs:42:7", "somewhere"))
        assertEquals(2, gone.size); assertTrue(gone.all { "crates/x/src/lib.rs" in it.text })
    }

    @Test fun aLongLineIsCutOnACharBoundary() {
        assertEquals(SPAN_CHARS, spans("error: " + "ж".repeat(400))[0].text.scalarList().size)
    }
}

class LlmTest {
    @Test fun diffsAndSourceAreCodeLogsAreNot() {
        assertTrue(isCode("diff --git a/x b/x\n--- a/x\n+++ b/x\n@@ -1 +1 @@\n-a\n+b"))
        assertTrue(isCode("fn a() {\n    let x = 1;\n    x;\n}\n"))
        assertFalse(isCode("running 3 tests\ntest a ... ok\nerror: boom\n"))
    }

    @Test fun thePromptFileCarriesBothPlaceholdersOnce() {
        assertEquals(1, Regex("\\{\\{must_keep}}").findAll(Prompt.DEFAULT.user).count())
        assertEquals(1, Regex("\\{\\{text}}").findAll(Prompt.DEFAULT.user).count())
        assertTrue(Prompt.DEFAULT.system.isNotEmpty())
    }

    @Test fun onlyAnthropicCredentialsBecomeCompressorAuth() {
        val headers = listOf("authorization" to "Bearer sk-test")
        assertNotNull(Auth.fromHeaders(headers, "anthropic"))
        for (p in listOf("openai", "gemini", null)) assertNull(Auth.fromHeaders(headers, p), "$p")
    }

    @Test fun authNeedsACredential() {
        assertNull(Auth.of(listOf("anthropic-version" to "x")))
        assertNotNull(Auth.of(listOf("X-Api-Key" to "k")))
    }

    @Test fun versionIsThePromptHashAndModel() {
        val v = version("m"); assertEquals(14, v.length); assertTrue(v.endsWith("+m"))
    }
}
