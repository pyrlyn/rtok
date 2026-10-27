package rtok.proxy

import kotlinx.serialization.json.*
import rtok.plugin.sdk.WireRequest

internal fun js(s: String): JsonElement = Json.parseToJsonElement(s)

internal fun req(
    wire: Wire,
    s: String,
) = WireRequest(wire, js(s))
