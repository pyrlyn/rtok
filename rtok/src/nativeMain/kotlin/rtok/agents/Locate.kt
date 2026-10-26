// Port of src/agents/locate.rs (lookup part): `key`, `variant_for`, `user_path`, `locate`, `present`, `app_paths`,
// `programs`, `cli`, `cli_program`. The interactive `resolve_input`/`ask_*` side lands with `rtok agents`.
package rtok.agents

import rtok.config.Config
import rtok.sys.isDir
import rtok.sys.isFile

object Locate {
    /** The config key a user path for [host] lives under. */
    fun key(host: String) = "setup.paths.$host"

    /**
     * Which of [host]'s variants [path] names: a desktop app's file name first, then a binary's name, then a bundle
     * or folder for the variant with apps, else the first variant with binaries. Needs no file on disk.
     */
    fun variantFor(
        host: Host,
        path: String,
    ): Variant? {
        val name = path.trimEnd('/').substringAfterLast('/').lowercase()
        val stem = name.substringBeforeLast('.')
        val vs = host.variants
        val bundle = isDir(path) || name.endsWith(".app")
        return vs.firstOrNull { v -> v.apps.any { it.substringAfterLast('/').equals(name, ignoreCase = true) } }
            ?: vs.firstOrNull { v -> v.bins.any { it.equals(stem, ignoreCase = true) } }
            ?: vs.firstOrNull { it.apps.isNotEmpty() }.takeIf { bundle }
            ?: vs.firstOrNull { it.bins.isNotEmpty() }
            ?: vs.firstOrNull()
    }

    /** The user's path for [host], when it names variant [v]. */
    private fun userPath(
        cfg: Config,
        host: String,
        v: Variant,
    ): String? {
        val path = cfg.setup.paths[host]?.let(HostBins::expandApp)
        val agent = Hosts.host(host)
        return path?.takeIf { agent != null && variantFor(agent, it)?.name == v.name }
    }

    /** Where [v] of [host] is: the user's path, then an app bundle, then a binary on PATH; null = not installed. */
    fun locate(
        cfg: Config,
        host: String,
        v: Variant,
    ): String? =
        (listOfNotNull(userPath(cfg, host, v)) + v.apps.map(HostBins::expandApp)).firstNotNullOfOrNull(HostBins::probeInstalled)
            ?: v.bins.firstNotNullOfOrNull(HostBins::findOnPath)

    /** True when [locate] finds the app itself (a leftover config folder does not count, T426). */
    fun present(
        cfg: Config,
        host: String,
        v: Variant,
    ) = locate(cfg, host, v) != null

    /** The paths a desktop variant's app may be at, the user's first. */
    fun appPaths(
        cfg: Config,
        host: String,
        v: Variant,
    ): List<String> = (listOfNotNull(userPath(cfg, host, v)) + v.apps.map(HostBins::expandApp)).map { HostBins.probeInstalled(it) ?: it }

    /** What to spawn for [v]'s CLI, in order: the user's binary, then each binary name (or its sandboxed path). */
    fun programs(
        cfg: Config,
        host: String,
        v: Variant,
    ): List<String> = listOfNotNull(userPath(cfg, host, v)?.takeIf(::isFile)) + v.bins.mapNotNull(HostBins::hostProgram)

    private fun cliVariant(host: String) = Hosts.host(host)?.variants?.firstOrNull { it.kind == Kind.Cli && it.bins.isNotEmpty() }

    /** Where [host]'s CLI is. */
    fun cli(
        cfg: Config,
        host: String,
    ): String? = cliVariant(host)?.let { locate(cfg, host, it) }

    /** What to spawn for [host]'s CLI. */
    fun cliProgram(
        cfg: Config,
        host: String,
    ): String? = cliVariant(host)?.let { programs(cfg, host, it).firstOrNull() }
}
