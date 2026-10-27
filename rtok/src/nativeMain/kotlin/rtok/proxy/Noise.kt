// Port of src/proxy/noise.rs (T432): terminal noise out of tool results and user text blocks before the
// request goes upstream, through the store's cleaner. Pure and fail-open: an unparseable or already clean
// body is forwarded as it arrived, so the prompt cache keeps hitting.
package rtok.proxy

import rtok.plugin.sdk.*
import rtok.store.Sanitize
import rtok.store.SerdeJson

object Noise {
    fun strip(
        dialect: ToolResults,
        original: ByteArray,
    ): ByteArray {
        val body = SerdeJson.parse(original.decodeToString()) ?: return original
        val req = WireRequest(dialect, body)
        var changed = false
        val refs: List<BodyRef> = req.toolResults() + req.liveBlobs()
        // Paths are resolved against the current body, so each ref is cleaned in turn.
        for (r in refs) {
            val (out, hit) = Sanitize.strings(r.content, Sanitize::terminalNoise)
            if (hit) {
                r.content = out
                changed = true
            }
        }
        return if (changed) SerdeJson.encode(req.body).encodeToByteArray() else original
    }
}
