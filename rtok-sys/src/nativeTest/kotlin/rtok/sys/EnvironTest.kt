package rtok.sys
import kotlin.test.*
class EnvironTest {
    @Test fun environListsPath() { assertTrue(environ().any { it.first == "PATH" && it.second.isNotEmpty() }) }
    @Test fun secondLockIsRefused() {
        val p = "${tempDir()}/rtok-lock-${pid()}"; val a = assertNotNull(tryLockExclusive(p)); assertNull(tryLockExclusive(p)); platform.posix.close(a); platform.posix.unlink(p)
    }
}
