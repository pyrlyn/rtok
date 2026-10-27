// Port of the policy half of src/proxy/flex.rs (T385.5): OpenAI Flex on the bulk and internal lanes. `apply`
// sets `service_tier = "flex"`, `Retry` schedules what a Flex 429 gets. The send loop (`flex::send`) arrives
// with the proxy server, which owns the HTTP client.
package rtok.proxy

import kotlinx.serialization.json.*
import rtok.config.FlexPolicy
import rtok.store.SerdeJson
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

object Flex {
    private const val TIER = "service_tier"
    private const val FLEX = "flex"

    /** Standard processing, the guide's fallback tier. */
    private const val AUTO = "auto"

    /** Every retry holds the client's connection open, so the count has a ceiling. */
    private const val MAX_RETRIES = 5u
    internal val MAX_DELAY = 30.seconds

    /** The 15 minutes the guide's SDK samples raise the timeout to. */
    private const val MIN_TIMEOUT_S: ULong = 900u

    /** The lane's read timeout: its own, else the proxy's, never under 15 min when Flex picks it. */
    fun laneTimeoutS(
        flex: Boolean,
        laneS: ULong,
        proxyS: ULong,
    ): ULong =
        when {
            laneS != 0uL -> laneS
            flex -> maxOf(proxyS, MIN_TIMEOUT_S)
            else -> proxyS
        }

    /** `Retry-After` as delta-seconds only; the HTTP-date form and anything else is ignored. */
    fun retryAfter(header: String?): Duration? {
        val v = header?.trim()
        return if (v.isNullOrEmpty() || !v.all { it in '0'..'9' }) null else v.toLongOrNull()?.seconds
    }

    private fun obj(body: ByteArray) = SerdeJson.parse(body.decodeToString()) as? JsonObject

    /** `service_tier = "flex"` on [body], or null: not OpenAI, not an object, or a client tier without `force`. */
    fun apply(
        provider: String,
        body: ByteArray,
        flex: FlexPolicy,
    ): ByteArray? {
        val parsed = if (provider == Wires.OPENAI_PROVIDER) obj(body) else null
        val chosen = parsed?.get(TIER)
        val free = chosen == null || chosen is JsonNull || (flex.force && chosen != JsonPrimitive(FLEX))
        return if (parsed != null && free) setTier(body, FLEX) else null
    }

    /** [body] with the tier set: spliced after the opening brace when absent, re-serialised when present. */
    internal fun setTier(
        body: ByteArray,
        tier: String,
    ): ByteArray? {
        val o = obj(body)
        return when {
            o == null -> {
                null
            }

            TIER in o -> {
                SerdeJson.encode(JsonObject(o + (TIER to JsonPrimitive(tier)))).encodeToByteArray()
            }

            else -> {
                val open = body.indexOfFirst { it.toInt().toChar() !in " \t\n\r" }
                val field = "\"$TIER\":\"$tier\"" + if (o.isEmpty()) "" else ","
                body.copyOfRange(0, open + 1) + field.encodeToByteArray() + body.copyOfRange(open + 1, body.size)
            }
        }
    }

    private enum class OnBusy { NONE, BACKOFF, DEFAULT }

    /** How one request rtok sent as Flex is retried after a `429`. */
    class Retry private constructor(
        /** The body as it was before rtok set the tier. */
        private val base: ByteArray,
        private val onBusy: OnBusy,
        private val retries: UInt,
        private val backoff: Duration,
    ) {
        /**
         * The wait before retry [n] (0-based) and the body to send then (null = the same), or null once no
         * retry is left. The wait is the longer of ours and the server's; one over [MAX_DELAY] makes `backoff`
         * give up. `default` never waits: its single retry goes to another tier.
         */
        fun next(
            n: UInt,
            server: Duration?,
        ): Pair<Duration, ByteArray?>? {
            val tooLong = server != null && server > MAX_DELAY
            return when {
                onBusy == OnBusy.BACKOFF && n < retries && !tooLong -> {
                    val ours = minOf(backoff * (1L shl n.toInt()).toDouble(), MAX_DELAY)
                    maxOf(ours, server ?: Duration.ZERO) to null
                }

                onBusy == OnBusy.DEFAULT && n == 0u -> {
                    fallback()?.let { Duration.ZERO to it }
                }

                else -> {
                    null
                }
            }
        }

        /** The client's own tier when `force` overwrote one, `auto` only when it sent none. */
        private fun fallback(): ByteArray? {
            val sent = obj(base)?.get(TIER)
            return when {
                obj(base) == null -> null
                sent != null && sent !is JsonNull -> base
                else -> setTier(base, AUTO)
            }
        }

        companion object {
            /** null when the policy is `none` (or unknown): nothing to retry. */
            fun of(
                base: ByteArray,
                flex: FlexPolicy,
            ): Retry? {
                val on =
                    when (flex.on429) {
                        "backoff" -> OnBusy.BACKOFF
                        "default" -> OnBusy.DEFAULT
                        else -> OnBusy.NONE
                    }
                return if (on == OnBusy.NONE) null else Retry(base, on, minOf(flex.retries, MAX_RETRIES), flex.backoffMs.toLong().milliseconds)
            }
        }
    }
}
