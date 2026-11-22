package rtok.mcp

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class RoundtripTest {
    @Test fun aCharacterSplitAcrossTwoReadsIsDecodedWhole() {
        // The two bytes of "ж" (D0 B6) arrive in separate writes, so the probe reads them in separate chunks.
        val script = """printf '{"jsonrpc":"2.0","id":2,"result":{"tools":[],"name":"\320'; sleep 0.3; printf '\266"}}\n'"""
        val replies = Roundtrip.run("/bin/sh", listOf("-c", script), emptyMap(), null, 10.seconds).getOrThrow()
        val result = replies.single().jsonObject["result"]!!.jsonObject
        assertEquals("ж", result["name"]!!.jsonPrimitive.content)
    }
}
