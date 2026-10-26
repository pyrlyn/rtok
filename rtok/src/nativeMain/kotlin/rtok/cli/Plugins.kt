// Port of src/cli/plugins.rs: the `rtok plugins` table (`--json` waits for the model's stats page).
package rtok.cli

import rtok.config.Config
import rtok.plugins.Registry

val PLUGINS = CommandSpec("plugins", "List plugins, whether each is on, and where it runs") { inv ->
    inv.io.out(Registry.new(Config.loadWith(inv.configFile, null)).table())
}
