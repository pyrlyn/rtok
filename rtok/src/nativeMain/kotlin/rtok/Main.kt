package rtok

import rtok.cli.run
import rtok.db.Db
import rtok.db.room.RoomDbDriver

/** Wires the adapters (SQLite via androidx.sqlite) once, then runs the CLI. */
fun main(args: Array<String>) {
    Db.installIfAbsent(RoomDbDriver)
    run(args)
}
