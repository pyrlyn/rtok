// Port of the src/measure/dictionary.rs tests (T403).
@file:Suppress("MagicNumber")

package rtok.measure

import kotlin.test.Test
import kotlin.test.assertEquals

class DictionaryTest {
    private val path = "/Users/x/apps/rtok/src/proxy/mod.rs"
    private val len = path.length.toLong()

    private fun found(text: String) = ArrayList<Pair<Boolean, String>>().also { v -> Dictionary.scan(text) { p, t -> v += p to t } }

    @Test fun pathsAndIdentifiersAreToldApartFromProseUrlsAndHashes() {
        assertEquals(
            listOf(true to path, false to "request_body_len"),
            found("see $path, fn request_body_len() and https://example.com/a/very/long/path plus deadbeefcafe1234 and implementation"),
        )
        assertEquals(listOf(false to "camelCaseIdentifier"), found("camelCaseIdentifier"))
    }

    @Test fun savingIsNetOfTheDefinitionAndIgnoresSingletons() {
        val c = Dictionary.Counts()
        c.add("$path $path $path $path src/only/once/in/the/request.rs", true)
        val r = c.saving()
        assertEquals(4 * len, r.paths.repeated)
        assertEquals(2 * (len - 2) - 4, r.paths.saved)
        assertEquals(DictKind(), r.idents)
    }

    @Test fun onlyToolResultsAreRewritable() {
        val c = Dictionary.Counts()
        c.add("$path $path $path", false)
        c.add("$path $path", true)
        val r = c.saving()
        assertEquals(5 * len to 0L, r.paths.repeated to r.paths.saved)
    }

    private fun transcript(compactAfter: Int?): Parsed {
        val lines = ArrayList<String>()
        for (i in 0 until 4) {
            lines +=
                """{"type":"assistant","message":{"id":"m$i","usage":{"input_tokens":1},""" +
                """"content":[{"type":"tool_use","id":"t$i","name":"Read","input":{"file_path":"$path"}}]}}"""
            if (compactAfter == i) lines += """{"type":"system","subtype":"compact_boundary"}"""
        }
        return parseJsonl(lines.joinToString("\n"))
    }

    @Test fun foldSamplesEachRequestOfAShortSession() {
        val row = DictionaryRow()
        Dictionary.fold(transcript(null), row)
        assertEquals(4L to (2 + 3 + 4) * len, row.requests to row.paths.repeated)
        assertEquals(0L, row.paths.saved)
    }

    @Test fun foldStartsOverAfterACompaction() {
        val row = DictionaryRow()
        Dictionary.fold(transcript(1), row)
        assertEquals((2 + 2) * len, row.paths.repeated)
    }
}
