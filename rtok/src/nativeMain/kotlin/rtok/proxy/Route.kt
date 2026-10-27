// Port of src/proxy/route.rs: model routing for the `internal` and `bulk` lanes (T385.10, D9, I-101).
// A rule swaps the request's `model` for a cheaper one; nothing else about the body changes. Closed until
// the operator names the bench behind it (`gate`). Gemini names the model in the path, so it never matches.
package rtok.proxy

import kotlinx.serialization.json.*
import rtok.config.RouteRule
import rtok.store.SerdeJson

object Route {
    /** What a rule changed, for the ledger. */
    data class Routed(
        val body: ByteArray,
        val from: String,
        val to: String,
        /** The provider slug to record, when the rule names one. */
        val provider: String?,
    )

    /** A rule acts only when it names what it matches, where it goes and the bench behind it. */
    private fun active(rule: RouteRule) = rule.from.isNotEmpty() && rule.to.isNotBlank() && rule.gate.isNotBlank()

    /** An exact id, or a prefix when the pattern ends in `*`. */
    internal fun matches(
        pattern: String,
        model: String,
    ) = if (pattern.endsWith('*')) model.startsWith(pattern.dropLast(1)) else pattern == model

    /** Re-point [body] at the rule's model, or null to forward it as it is. */
    fun apply(
        rule: RouteRule,
        body: ByteArray,
    ): Routed? {
        val parsed = if (active(rule)) SerdeJson.parse(body.decodeToString()) as? JsonObject else null
        val to = rule.to.trim()
        val from = parsed?.str("model")?.takeIf { m -> m != to && rule.from.any { matches(it, m) } }
        return if (parsed == null || from == null) {
            null
        } else {
            val out = JsonObject(parsed + ("model" to JsonPrimitive(to)))
            Routed(SerdeJson.encode(out).encodeToByteArray(), from, to, rule.provider.trim().ifEmpty { null })
        }
    }
}
