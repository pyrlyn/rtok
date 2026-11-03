package rtok

import db.Db
import db.room.RoomDbDriver
import rtok.cli.run
import rtok.otel.i
import rtok.trace.Trace

/** Wires the adapters (SQLite via androidx.sqlite) and the `config validate` rules check once, then runs the CLI. */
fun main(args: Array<String>) {
    Db.installIfAbsent(RoomDbDriver)
    rtok.plugins.cmd.Rules
        .installValidateCheck()
    val code =
        Trace.span("rtok") {
            run(args).also { Trace.annotate(listOf(i("process.exit.code", it.toLong()))) }
        }
    Trace.flush()
    kotlin.system.exitProcess(code)
}
