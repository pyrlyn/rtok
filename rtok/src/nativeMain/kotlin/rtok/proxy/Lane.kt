// Port of src/proxy/lane.rs (T385.1): which kind of traffic a proxied request is. A path that names its own
// kind decides; an `x-rtok-lane` header or `/lane/<name>/` prefix picks among what a path cannot say.
package rtok.proxy

import rtok.config.LanePolicy
import rtok.config.Lanes
import rtok.config.RouteRule
import rtok.config.RoutingPolicy
import rtok.lane.LaneKinds

enum class Lane(
    val laneName: String,
    /** The `calls.kind` this lane is recorded under. */
    val kind: String,
) {
    Agent("agent", LaneKinds.AGENT),
    Bulk("bulk", LaneKinds.BULK),
    Batch("batch", LaneKinds.BATCH),
    Files("files", LaneKinds.FILES),
    Embeddings("embeddings", LaneKinds.EMBEDDINGS),
    Meta("meta", LaneKinds.META),
    Internal("internal", LaneKinds.INTERNAL),
    ;

    /**
     * What the proxy may change on this lane (T385.2). `agent` allows every rewrite, never Flex silently, no
     * cap and its wire's upstream; `batch`/`files` are never opened; the rest read `[proxy.lanes.<lane>]`.
     */
    fun policy(lanes: Lanes): LanePolicy =
        when (this) {
            Agent -> {
                LanePolicy(
                    compress = true,
                    toon = true,
                    toolsRewrite = true,
                    contextManagement = true,
                    semanticCache = true,
                    maxQueued = 0u,
                )
            }

            Batch, Files -> {
                LanePolicy()
            }

            Bulk -> {
                lanes.bulk
            }

            Embeddings -> {
                lanes.embeddings
            }

            Meta -> {
                lanes.meta
            }

            Internal -> {
                lanes.internal
            }
        }

    /** The model-routing rule (T385.10); only `internal` and `bulk`, only when routing is on. */
    fun route(routing: RoutingPolicy): RouteRule? =
        when {
            !routing.enabled -> null
            this == Bulk -> routing.bulk
            this == Internal -> routing.internal
            else -> null
        }

    /** Bodies forwarded verbatim whatever the config says, past usage-capture shaping too. */
    val passesThrough: Boolean get() = this == Batch || this == Files

    /** The lane of one request and the path to forward (the `/lane/<name>` prefix removed). */
    data class Classified(
        val lane: Lane,
        val path: String,
    )

    companion object {
        private const val PREFIX = "/lane/"
        private val BATCH = listOf("/v1/messages/batches", "/v1/batches", "/v1beta/batches")
        private val FILES = listOf("/v1/files", "/v1beta/files", "/upload/v1beta/files")
        private val META = listOf("/v1/messages/count_tokens", "/v1/models", "/v1beta/models")

        fun fromName(name: String): Lane? = name.trim().let { n -> entries.firstOrNull { it.laneName.equals(n, ignoreCase = true) } }

        /** Classify [path] + the `x-rtok-lane` header value. Unknown paths and sync chat fall to `agent`. */
        fun classify(
            path: String,
            header: String?,
        ): Classified {
            val (prefixed, rest) = stripPrefix(path)
            val marked = prefixed ?: header?.let(::fromName)
            return Classified(byPath(rest) ?: marked ?: Agent, rest)
        }

        /** `/lane/<name>/rest` -> (lane, `/rest`). An unknown name is not ours to strip. */
        private fun stripPrefix(path: String): Pair<Lane?, String> {
            val rest = path.removePrefix(PREFIX).takeIf { path.startsWith(PREFIX) && '/' in it }
            val lane = rest?.let { fromName(it.substringBefore('/')) }
            return if (lane == null) null to path else lane to "/" + rest.substringAfter('/')
        }

        private fun under(
            path: String,
            base: String,
        ) = path.startsWith(base) && path.substring(base.length).let { it.isEmpty() || it.startsWith('/') }

        /** The lane a path names by itself, across the Anthropic, OpenAI and Gemini surfaces. */
        private fun byPath(path: String): Lane? {
            val gemini = (under(path, "/v1beta/models") || under(path, "/v1/models")) && ':' in path
            return when {
                gemini -> {
                    when (path.substringAfterLast(':')) {
                        "batchGenerateContent", "asyncBatchEmbedContent" -> Batch
                        "embedContent", "batchEmbedContents" -> Embeddings
                        "countTokens" -> Meta
                        else -> null
                    }
                }

                BATCH.any { under(path, it) } -> {
                    Batch
                }

                FILES.any { under(path, it) } -> {
                    Files
                }

                under(path, "/v1/embeddings") -> {
                    Embeddings
                }

                META.any { under(path, it) } -> {
                    Meta
                }

                else -> {
                    null
                }
            }
        }
    }
}
