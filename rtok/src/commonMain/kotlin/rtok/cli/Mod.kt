// Port of src/cli/mod.rs (subset). Each subcommand lands in its own file as phases complete.
package rtok.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main

class Rtok : CliktCommand(name = "rtok") {
    override fun run() = Unit
}

fun run(args: Array<String>) = Rtok().main(args)
