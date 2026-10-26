// Small path helpers for host config files (Rust `Path::join`, `parent`, `with_file_name`, jsonc.rs `lone_sibling`).
package rtok.agents

import rtok.sys.exists

internal fun join(
    dir: String,
    name: String,
) = if (dir.isEmpty() || dir.endsWith('/')) "$dir$name" else "$dir/$name"

internal fun parentOf(path: String) = path.substringBeforeLast('/', ".").ifEmpty { "/" }

internal fun withFileName(
    path: String,
    name: String,
) = join(parentOf(path), name)

/** jsonc.rs `lone_sibling`: `<name>.jsonc` when only it exists, else [json] itself. */
fun loneSibling(json: String): String {
    val sibling = json.substringBeforeLast('.') + ".jsonc"
    return if (!exists(json) && exists(sibling)) sibling else json
}

/** [loneSibling] only when [json] is the host's default file name (`settings.json`, `mimocode.json`). */
internal fun siblingIfNamed(
    json: String,
    name: String,
) = if (json.substringAfterLast('/') == name) loneSibling(json) else json
