// Port of src/agents/plugin_version.rs: the `.rtok-plugin-version` file every plugin tree carries, rtok's own install
// receipt (`plugins.json`), what is installed and what is available per source, and the update decision (T279).
package rtok.agents

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import rtok.config.Config
import rtok.config.writeFile
import rtok.sys.ProgressRunner
import rtok.sys.exists
import rtok.sys.isFile
import rtok.sys.readToString
import semver.VersionKey

@Serializable
enum class Source {
    @SerialName("github")
    Github,

    @SerialName("local")
    Local,

    @SerialName("marketplace")
    Marketplace,
    ;

    override fun toString() = name.lowercase()

    companion object {
        fun parse(s: String): Source =
            entries.firstOrNull { it.toString() == s.lowercase() }
                ?: throw IllegalArgumentException("unknown plugin source \"$s\" (expected github, local or marketplace)")
    }
}

/** `{schema, plugin, version, source?}` at a plugin tree's root. */
@Serializable
data class VersionFile(
    val schema: Int = PluginVersion.SCHEMA,
    val plugin: String,
    val version: String,
    val source: Source? = null,
) {
    fun write(path: String) = writeFile(path, PluginVersion.JSON.encodeToString(serializer(), this) + "\n")

    companion object {
        fun read(path: String): VersionFile {
            val file =
                runCatching { PluginVersion.JSON.decodeFromString(serializer(), readToString(path)) }
                    .getOrElse { throw IllegalStateException("$path: invalid plugin version file") }
            check(file.schema == PluginVersion.SCHEMA) { "$path: unknown schema ${file.schema} (rtok understands schema ${PluginVersion.SCHEMA})" }
            return file
        }
    }
}

/** One host's row in rtok's receipt. */
@Serializable
data class ReceiptEntry(
    val source: Source,
    @SerialName("ref") val reference: String,
    val marketplace: String? = null,
    val path: String,
    val version: String,
    @SerialName("installed_at") val installedAt: String,
)

/** rtok's receipt: host → [ReceiptEntry], written with sorted keys like Rust's `BTreeMap`. */
data class Receipt(
    val rows: Map<String, ReceiptEntry> = emptyMap(),
) {
    fun get(host: String) = rows[host]

    fun upsert(
        host: String,
        entry: ReceiptEntry,
    ) = Receipt(rows + (host to entry))

    fun delete(host: String) = Receipt(rows - host)

    fun write(path: String) {
        val sorted = rows.entries.sortedBy { it.key }.associate { it.key to it.value }
        writeFile(path, PluginVersion.PRETTY.encodeToString(MAP, sorted) + "\n")
    }

    companion object {
        private val MAP = MapSerializer(String.serializer(), ReceiptEntry.serializer())

        fun read(path: String): Receipt {
            if (!exists(path)) return Receipt()
            return runCatching { Receipt(PluginVersion.JSON.decodeFromString(MAP, readToString(path))) }
                .getOrElse { throw IllegalStateException("$path: invalid plugin receipt") }
        }
    }
}

data class Installed(
    val version: String,
    val source: Source,
    val legacy: Boolean,
)

data class Available(
    val version: String,
    val source: Source,
)

sealed interface Decision {
    data class Skip(
        val reason: String,
    ) : Decision

    data object Update : Decision

    data class Reinstall(
        val reason: String,
    ) : Decision

    data object Install : Decision

    data class SkipOlder(
        val warning: String,
    ) : Decision
}

@OptIn(ExperimentalSerializationApi::class, ExperimentalForeignApi::class)
object PluginVersion {
    const val SCHEMA = 1
    const val FILE = ".rtok-plugin-version"
    val JSON = Json { ignoreUnknownKeys = true }
    val PRETTY =
        Json {
            prettyPrint = true
            prettyPrintIndent = "  "
        }

    private fun env(name: String) = platform.posix.getenv(name)?.toKString()

    /** The default receipt: per-OS state dir (macOS app support, Windows LOCALAPPDATA, else XDG state). */
    fun defaultReceiptPath(
        home: String,
        xdgStateHome: String?,
    ): String =
        if (McpPaths.macos) {
            "$home/Library/Application Support/rtok/plugins.json"
        } else {
            "${xdgStateHome ?: "$home/.local/state"}/rtok/plugins.json"
        }

    fun receiptPath(cfg: Config): String = cfg.pluginReceiptPath ?: defaultReceiptPath(McpPaths.home(), env("XDG_STATE_HOME"))

    /** `base+g<sha>[.dirty]` from `git describe --always --dirty`. */
    fun localVersion(
        base: String,
        describe: String,
    ): String {
        val dirty = describe.endsWith("-dirty")
        val rest = describe.removeSuffix("-dirty")
        val sha = if ("-g" in rest) rest.substringAfterLast("-g") else rest
        return "${base.substringBefore('+')}+g$sha${if (dirty) ".dirty" else ""}"
    }

    /** The version file first, then the receipt, then the host's own record (when SemVer), else 0.0.0. */
    fun installed(
        versionFile: VersionFile?,
        receipt: ReceiptEntry?,
        hostRecordVersion: String?,
        fallback: Source,
    ) = Installed(
        version = versionFile?.version ?: receipt?.version ?: hostRecordVersion?.takeIf { VersionKey.parse(it) != null } ?: "0.0.0",
        source = versionFile?.source ?: receipt?.source ?: fallback,
        legacy = versionFile == null,
    )

    /** What [source] offers: the running binary (GitHub), the catalog's file (marketplace), or the local tree + git. */
    fun available(
        source: Source,
        localDir: String,
        catalogDir: String?,
        running: String,
    ): Available {
        val version =
            when (source) {
                Source.Github -> running
                Source.Marketplace -> catalogDir?.let { "$it/$FILE" }?.takeIf(::isFile)?.let { VersionFile.read(it).version } ?: running
                Source.Local -> VersionFile.read("$localDir/$FILE").version.let { v -> gitDescribe(localDir)?.let { localVersion(v, it) } ?: v }
            }
        return Available(version, source)
    }

    private fun gitDescribe(dir: String): String? =
        runCatching {
            val (out, _) = ProgressRunner("git describe", "git", listOf("-C", dir, "describe", "--always", "--dirty")).runCapped(DESCRIBE_CAP)
            out.stdout
                .decodeToString()
                .trim()
                .takeIf { out.exitCode == 0 }
        }.getOrNull()

    private const val DESCRIBE_CAP = 4096L

    private fun build(v: String) = v.substringAfter('+', "")

    private fun cmp(
        a: String,
        b: String,
    ): Int = compareValues(VersionKey.parse(a), VersionKey.parse(b))

    fun decide(
        installed: Installed?,
        available: Available,
        force: Boolean,
    ): Decision {
        val c = installed?.let { cmp(available.version, it.version) } ?: 0
        return when {
            installed == null -> Decision.Install
            force -> Decision.Reinstall("forced")
            installed.source != available.source -> Decision.Reinstall("source changed from ${installed.source} to ${available.source}")
            c > 0 -> Decision.Update
            c < 0 -> Decision.SkipOlder("available ${available.version} is older than installed ${installed.version}")
            build(available.version) == build(installed.version) -> Decision.Skip("${installed.version} up to date")
            else -> Decision.Update
        }
    }

    /** `YYYY-MM-DDTHH:MM:SSZ` now. */
    fun nowIso() = "${rtok.log.stamp(rtok.log.now()).replaceFirst(' ', 'T')}Z"
}
