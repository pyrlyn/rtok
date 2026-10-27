// Port of src/cli/man_page.rs: `rtok man` prints `rtok.1`; `--dir DIR` writes it and a page per subcommand.
package rtok.cli

import rtok.VERSION
import rtok.man.Man

val MAN: CommandSpec =
    CommandSpec(
        "man",
        "Print the rtok(1) man page (roff), or write every page with --dir",
        options = listOf(OptSpec.Value("dir", "Write `rtok.1` and a page for every subcommand into this directory", "DIR")),
    ) { inv ->
        val dir = inv.value("dir")
        if (dir == null) inv.io.out(Man.print(RTOK, VERSION)) else Man.writeAll(RTOK, dir, VERSION).forEach(inv.io::println)
    }
