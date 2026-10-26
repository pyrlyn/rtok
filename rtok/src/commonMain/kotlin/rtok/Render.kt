// Port of src/render.rs (the parts ported so far).
package rtok

/** The `rtok plugins` table: `id`, `enabled`, comma-joined surfaces, columns padded to 10. */
fun pluginsTable(rows: List<Triple<String, Boolean, List<String>>>): String =
    buildString {
        append("id".padEnd(10)).append("enabled".padEnd(10)).append("surfaces\n")
        for ((id, on, surfaces) in rows) {
            append(id.padEnd(10)).append((if (on) "on" else "off").padEnd(10)).append(surfaces.joinToString(",")).append('\n')
        }
    }
