// Port of src/lane.rs: the `calls.kind` of each proxy lane and the `x-rtok-lane` header, shared by the proxy,
// stats and otel.
package rtok.lane

import rtok.store.BATCH_CALL_KIND

object LaneKinds {
    const val AGENT = "api_request"
    const val BULK = "api_request:bulk"
    const val BATCH = BATCH_CALL_KIND
    const val FILES = "api_request:files"
    const val EMBEDDINGS = "api_request:embeddings"
    const val META = "api_request:meta"
    const val INTERNAL = "api_request:internal"
    const val HEADER = "x-rtok-lane"
    private const val PREFIX = "api_request:"

    /** The bare agent lane or any `api_request:<lane>`. */
    fun isApiRequest(kind: String) = kind == AGENT || kind.startsWith(PREFIX)

    /** The lane a `kind` names; plain agent turns are `agent`. */
    fun laneOfKind(kind: String): String = if (kind.startsWith(PREFIX)) kind.removePrefix(PREFIX) else "agent"
}
