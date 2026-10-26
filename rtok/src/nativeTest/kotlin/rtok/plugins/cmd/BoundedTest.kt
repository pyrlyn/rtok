package rtok.plugins.cmd

import kotlin.test.*

class BoundedTest {
    @Test fun hostVisiblePrefixStopsAtThirtyThousandScalars() {
        val over = "x".repeat(Bounded.HOST_VISIBLE_CHARS + 1)
        val prefix = Bounded.hostVisiblePrefix(over)
        assertEquals(Bounded.HOST_VISIBLE_CHARS, prefix.length)
        val exact = "é".repeat(Bounded.HOST_VISIBLE_CHARS)
        assertEquals(exact, Bounded.hostVisiblePrefix(exact))
    }

    @Test fun boundedForms() = listOf(
        "sed -n '1,620p' src/hooks/types.rs", "sed -n 10p a; sed -n -e 1,5p -e 9p b",
        "cargo nextest run --workspace 2>&1 | tail -300", "cargo nextest run |& tail -n 50",
        "git log --oneline | head", "head -n 40 a.rs b.rs", "grep -rn -A3 -m 5 'a|b' src",
        "rg -C 2 needle", "grep -m 5 x f", "grep -A3 x f", "cd /repo && sed -n '1,80p' Cargo.toml",
        "cat -n src/main.rs", "rg -r -C 2 needle src",
    ).forEach { assertTrue(Bounded.isBounded(it), it) }

    @Test fun unboundedForms() = listOf(
        "cargo nextest run", "sed -n '/fn main/,\$p' a.rs", "sed 's/a/b/' a.rs", "tail -n +5 log",
        "tail -f log", "head -n -5 log", "grep -rn needle src", "sed -n 1,5p a | grep x",
        "make && tail -5 build.log", "cat a.rs", "cd /repo", "echo 'x | head'",
        "grep -rn -A3 'a|b' src", "cat -n a.rs b.rs",
    ).forEach { assertFalse(Bounded.isBounded(it), it) }

    @Test fun sameProgramForms() = listOf(
        "git status", "cargo build && cargo test", "cd x && cargo test", "cargo test; cargo clippy",
        "cargo test \\\n  --all", "mise exec -- cargo build && mise exec -- cargo test",
    ).forEach { assertFalse(Bounded.mixedChain(it), it) }

    @Test fun mixedProgramForms() = listOf(
        "cargo test && cargo clippy && git status", "mise exec -- cargo test && mise exec -- git status",
        "git log | grep foo", "echo one; cat two", "git status\ncat file.txt\nls",
    ).forEach { assertTrue(Bounded.mixedChain(it), it) }

    @Test fun backslashNewlineContinuationDoesNotSplitAPipeline() =
        assertEquals(1, Bounded.lex("cargo test \\\n  --all").size)

    @Test fun lexerBackslashEscapes() = listOf(
        """echo it\'s && cd sub""" to listOf(listOf("echo", "it's"), listOf("cd", "sub")),
        """echo "a\" b" && x""" to listOf(listOf("echo", "a\" b"), listOf("x")),
        """echo "a\\" && x""" to listOf(listOf("echo", "a\\"), listOf("x")),
        """echo "a\n" && x""" to listOf(listOf("echo", "a\\n"), listOf("x")),
        """echo 'a\' && x""" to listOf(listOf("echo", "a\\"), listOf("x")),
        """echo a\;b c""" to listOf(listOf("echo", "a;b", "c")),
        "cargo test \\\n --all" to listOf(listOf("cargo", "test", "--all")),
    ).forEach { (cmd, want) -> assertEquals(want, Bounded.stages(cmd), cmd) }
}
