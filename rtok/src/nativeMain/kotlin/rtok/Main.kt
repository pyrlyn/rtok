package rtok

import db.Db
import db.room.RoomDbDriver
import rtok.cli.run

/** Wires the adapters (SQLite via androidx.sqlite) and the `config validate` rules check once, then runs the CLI. */
fun main(args: Array<String>) {
    Db.installIfAbsent(RoomDbDriver)
    rtok.plugins.cmd.Rules
        .installValidateCheck()
    kotlin.system.exitProcess(run(args))
}
