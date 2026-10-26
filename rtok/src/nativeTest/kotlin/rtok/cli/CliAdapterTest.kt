package rtok.cli

import kotlin.test.*

/** The CLI parser adapter dispatches a `CommandSpec` tree. */
class CliAdapterTest {
    @Test fun aSubcommandActionRunsThroughTheParserAdapter() {
        var ran = ""
        val spec = CommandSpec("rtok", subcommands = listOf(CommandSpec("stats", action = { ran += "stats" }), CommandSpec("info", action = { ran += "info" })))
        assertEquals(0, cliParser.run(spec, arrayOf("info"), StdIo)); assertEquals("info", ran)
    }
}
