package rtok.store

import rtok.db.*
import kotlin.test.*

/** The store reaches SQLite only through `Db.driver`; a wrapping driver sees every connection the store opens. */
class DriverTest {
    @Test fun theStoreOpensConnectionsThroughTheConfiguredDriver() {
        val real = Db.driver
        val opened = ArrayList<String>()
        Db.driver =
            object : DbDriver {
                override fun open(path: String) = real.open(path).also { opened += path }

                override fun version() = real.version()
            }
        try {
            Store.openInMemory().use { assertEquals(0, it.countCalls()) }
        } finally {
            Db.driver = real
        }
        assertEquals(listOf(":memory:"), opened)
    }
}
