package rtok

import rtok.cli.run
import rtok.db.Db
import rtok.db.room.RoomDbDriver

/** Wires the adapters (SQLite via androidx.sqlite) and the `config validate` rules check once, then runs the CLI. */
fun main(args: Array<String>) {
    Db.installIfAbsent(RoomDbDriver)
    rtok.plugins.cmd.Rules.installValidateCheck()
    kotlin.system.exitProcess(run(args))
}
