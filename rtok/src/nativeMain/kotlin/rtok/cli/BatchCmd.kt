// Port of src/cli/batch.rs: `rtok batch submit|status|fetch` (T385.12.1), one blocking request chain against the proxy.
// The key itself is only read from the environment (`ANTHROPIC_API_KEY` / `OPENAI_API_KEY`).
package rtok.cli

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import rtok.BatchApi
import rtok.BatchException
import rtok.BatchProvider
import rtok.agents.ProxyEnv
import rtok.http.ktor.KtorHttp

private const val ID_HELP = "Batch id (`msgbatch_…` is Anthropic, `batch_…` OpenAI)"

private val TARGET =
    listOf(
        OptSpec.Value("provider", "Provider whose Batch API to call; default: from the id (`msgbatch_` is Anthropic)", "PROVIDER"),
        OptSpec.Value("url", "Proxy base URL (default: `[proxy] bind` and `port`)", "URL"),
    )

@OptIn(ExperimentalForeignApi::class)
private fun env(name: String) = platform.posix.getenv(name)?.toKString()

/** The provider from `--provider`, else from [id], else Anthropic; the base URL from `--url`, else the config. */
private fun Invocation.api(id: String?): BatchApi {
    val named =
        value("provider")?.let {
            BatchProvider.parse(it)
                ?: throw CliFailure("invalid value '$it' for '--provider <PROVIDER>'\n  [possible values: anthropic, openai]", ExitCode.USAGE)
        }
    val provider = named ?: id?.let(BatchProvider::ofId) ?: BatchProvider.Anthropic
    val base = value("url") ?: ProxyEnv.anthropic(loadConfig())
    return batch { BatchApi(base, provider, env(provider.keyEnv).orEmpty(), KtorHttp.FACTORY) }
}

@Suppress("SwallowedException") // the message is the whole error, as anyhow prints it
private fun <T> batch(body: () -> T): T =
    try {
        body()
    } catch (e: BatchException) {
        throw CliFailure(e.message.orEmpty())
    }

private val SUBMIT =
    CommandSpec(
        "submit",
        "Create a batch from a JSONL file of provider-shaped requests; prints the batch object",
        options = TARGET,
        args = listOf(ArgSpec("file", "One request per line (Anthropic: `custom_id` + `params`; OpenAI: `custom_id`, `method`, `url`, `body`)")),
        action = { inv ->
            val api = inv.api(null)
            inv.io.println(batch { api.submit(inv.requireArg("file")) })
        },
    )

private val STATUS =
    CommandSpec(
        "status",
        "Print the provider's current state of a batch",
        options = TARGET,
        args = listOf(ArgSpec("id", ID_HELP)),
        action = { inv ->
            val id = inv.requireArg("id")
            val api = inv.api(id)
            inv.io.println(batch { api.status(id) })
        },
    )

private val FETCH =
    CommandSpec(
        "fetch",
        "Write the results of a finished batch to a new file",
        options = TARGET,
        args = listOf(ArgSpec("id", ID_HELP), ArgSpec("out", "File to create; an existing file is never overwritten")),
        action = { inv ->
            val id = inv.requireArg("id")
            val out = inv.requireArg("out")
            val api = inv.api(id)
            val bytes = batch { api.fetch(id, out) }
            inv.io.println("wrote $bytes bytes to $out")
        },
    )

val BATCH =
    CommandSpec(
        "batch",
        "Provider Batch jobs (Anthropic, OpenAI) sent through `rtok proxy`",
        subcommands = listOf(SUBMIT, STATUS, FETCH),
    )
