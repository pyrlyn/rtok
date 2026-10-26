// mcp.rs `record`: every tool call becomes an `mcp_call` row with its I/O and token estimates.
package rtok.mcp

import kotlinx.serialization.json.JsonObject
import rtok.plugin.Runtime
import rtok.plugin.sdk.Class
import rtok.store.insertCall
import rtok.store.insertCallIo
import rtok.store.insertTokens
import rtok.store.upsertSession

object Record {
    private const val OTHER_HOST = 6

    fun call(
        cx: Runtime,
        plugin: String,
        name: String,
        args: JsonObject,
        result: String,
    ) {
        val argsS = args.toString()
        val before = cx.estimate(argsS, Class.Json).toLong()
        val after = cx.estimate(result, Class.Json).toLong()
        val host = cx.hostId ?: OTHER_HOST
        cx.store.upsertSession(cx.session, host, null, null, "mcp")
        val callId = cx.store.insertCall(cx.session, "mcp", "mcp_call", host, null, null, plugin, name)
        cx.store.insertCallIo(
            callId,
            argsS.encodeToByteArray(),
            result.encodeToByteArray(),
            cx.config.core.callIoInlineBytes
                .toInt(),
            cx.config.core.archiveDir,
        )
        cx.store.insertTokens(callId, null, "before", "estimate", before)
        cx.store.insertTokens(callId, null, "after", "estimate", after)
        cx.store.insertTokens(callId, plugin, "mcp", "estimate", after)
    }
}
