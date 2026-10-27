package globset

import kotlin.test.Test
import kotlin.test.assertEquals

/** globset 0.4.20's own `matches!`/`nmatches!` table (src/glob.rs tests), Unix cases. */
class GlobsetCasesTest {
    private data class Case(
        val glob: String,
        val path: String,
        val matches: Boolean,
        val options: GlobOptions,
    )

    private companion object {
        val DEFAULT = GlobOptions()
        val CASEI = GlobOptions(caseInsensitive = true)
        val SLASHLIT = GlobOptions(literalSeparator = true)
        val NOBSESC = GlobOptions(backslashEscape = false)
        val BSESC = GlobOptions(backslashEscape = true)
        val EALTRE = GlobOptions(backslashEscape = true, emptyAlternates = true)
    }

    private val cases =
        listOf(
            Case("a", "a", true, DEFAULT), // match1
            Case("a*b", "a_b", true, DEFAULT), // match2
            Case("a*b*c", "abc", true, DEFAULT), // match3
            Case("a*b*c", "a_b_c", true, DEFAULT), // match4
            Case("a*b*c", "a___b___c", true, DEFAULT), // match5
            Case("abc*abc*abc", "abcabcabcabcabcabcabc", true, DEFAULT), // match6
            Case("a*a*a*a*a*a*a*a*a", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", true, DEFAULT), // match7
            Case("a*b[xyz]c*d", "abxcdbxcddd", true, DEFAULT), // match8
            Case("*.rs", ".rs", true, DEFAULT), // match9
            Case("☃", "☃", true, DEFAULT), // match10
            Case("some/**/needle.txt", "some/needle.txt", true, DEFAULT), // matchrec1
            Case("some/**/needle.txt", "some/one/needle.txt", true, DEFAULT), // matchrec2
            Case("some/**/needle.txt", "some/one/two/needle.txt", true, DEFAULT), // matchrec3
            Case("some/**/needle.txt", "some/other/needle.txt", true, DEFAULT), // matchrec4
            Case("**", "abcde", true, DEFAULT), // matchrec5
            Case("**", "", true, DEFAULT), // matchrec6
            Case("**", ".asdf", true, DEFAULT), // matchrec7
            Case("**", "/x/.asdf", true, DEFAULT), // matchrec8
            Case("some/**/**/needle.txt", "some/needle.txt", true, DEFAULT), // matchrec9
            Case("some/**/**/needle.txt", "some/one/needle.txt", true, DEFAULT), // matchrec10
            Case("some/**/**/needle.txt", "some/one/two/needle.txt", true, DEFAULT), // matchrec11
            Case("some/**/**/needle.txt", "some/other/needle.txt", true, DEFAULT), // matchrec12
            Case("**/test", "one/two/test", true, DEFAULT), // matchrec13
            Case("**/test", "one/test", true, DEFAULT), // matchrec14
            Case("**/test", "test", true, DEFAULT), // matchrec15
            Case("/**/test", "/one/two/test", true, DEFAULT), // matchrec16
            Case("/**/test", "/one/test", true, DEFAULT), // matchrec17
            Case("/**/test", "/test", true, DEFAULT), // matchrec18
            Case("**/.*", ".abc", true, DEFAULT), // matchrec19
            Case("**/.*", "abc/.abc", true, DEFAULT), // matchrec20
            Case("**/foo/bar", "foo/bar", true, DEFAULT), // matchrec21
            Case(".*/**", ".abc/abc", true, DEFAULT), // matchrec22
            Case("test/**", "test/", true, DEFAULT), // matchrec23
            Case("test/**", "test/one", true, DEFAULT), // matchrec24
            Case("test/**", "test/one/two", true, DEFAULT), // matchrec25
            Case("some/*/needle.txt", "some/one/needle.txt", true, DEFAULT), // matchrec26
            Case("a[0-9]b", "a0b", true, DEFAULT), // matchrange1
            Case("a[0-9]b", "a9b", true, DEFAULT), // matchrange2
            Case("a[!0-9]b", "a_b", true, DEFAULT), // matchrange3
            Case("[a-z123]", "1", true, DEFAULT), // matchrange4
            Case("[1a-z23]", "1", true, DEFAULT), // matchrange5
            Case("[123a-z]", "1", true, DEFAULT), // matchrange6
            Case("[abc-]", "-", true, DEFAULT), // matchrange7
            Case("[-abc]", "-", true, DEFAULT), // matchrange8
            Case("[-a-c]", "b", true, DEFAULT), // matchrange9
            Case("[a-c-]", "b", true, DEFAULT), // matchrange10
            Case("[-]", "-", true, DEFAULT), // matchrange11
            Case("a[^0-9]b", "a_b", true, DEFAULT), // matchrange12
            Case("*hello.txt", "hello.txt", true, DEFAULT), // matchpat1
            Case("*hello.txt", "gareth_says_hello.txt", true, DEFAULT), // matchpat2
            Case("*hello.txt", "some/path/to/hello.txt", true, DEFAULT), // matchpat3
            Case("*hello.txt", "some\\path\\to\\hello.txt", true, DEFAULT), // matchpat4
            Case("*hello.txt", "/an/absolute/path/to/hello.txt", true, DEFAULT), // matchpat5
            Case("*some/path/to/hello.txt", "some/path/to/hello.txt", true, DEFAULT), // matchpat6
            Case("*some/path/to/hello.txt", "a/bigger/some/path/to/hello.txt", true, DEFAULT), // matchpat7
            Case("_[[]_[]]_[?]_[*]_!_", "_[_]_?_*_!_", true, DEFAULT), // matchescape
            Case("aBcDeFg", "aBcDeFg", true, CASEI), // matchcasei1
            Case("aBcDeFg", "abcdefg", true, CASEI), // matchcasei2
            Case("aBcDeFg", "ABCDEFG", true, CASEI), // matchcasei3
            Case("aBcDeFg", "AbCdEfG", true, CASEI), // matchcasei4
            Case("a,b", "a,b", true, DEFAULT), // matchalt1
            Case(",", ",", true, DEFAULT), // matchalt2
            Case("{a,b}", "a", true, DEFAULT), // matchalt3
            Case("{a,b}", "b", true, DEFAULT), // matchalt4
            Case("{**/src/**,foo}", "abc/src/bar", true, DEFAULT), // matchalt5
            Case("{**/src/**,foo}", "foo", true, DEFAULT), // matchalt6
            Case("{[}],foo}", "}", true, DEFAULT), // matchalt7
            Case("{foo}", "foo", true, DEFAULT), // matchalt8
            Case("{}", "", true, DEFAULT), // matchalt9
            Case("{,}", "", true, DEFAULT), // matchalt10
            Case("{*.foo,*.bar,*.wat}", "test.foo", true, DEFAULT), // matchalt11
            Case("{*.foo,*.bar,*.wat}", "test.bar", true, DEFAULT), // matchalt12
            Case("{*.foo,*.bar,*.wat}", "test.wat", true, DEFAULT), // matchalt13
            Case("foo{,.txt}", "foo.txt", true, DEFAULT), // matchalt14
            Case("foo{,.txt}", "foo", false, DEFAULT), // matchalt15
            Case("foo{,.txt}", "foo", true, EALTRE), // matchalt16
            Case("{a,b{c,d}}", "bc", true, DEFAULT), // matchalt17
            Case("{a,b{c,d}}", "bd", true, DEFAULT), // matchalt18
            Case("{a,b{c,d}}", "a", true, DEFAULT), // matchalt19
            Case("abc/def", "abc/def", true, SLASHLIT), // matchslash1
            Case("abc?def", "abc/def", false, SLASHLIT), // matchslash2
            Case("abc*def", "abc/def", false, SLASHLIT), // matchslash3
            Case("abc[/]def", "abc/def", true, SLASHLIT), // matchslash4
            Case("abc\\def", "abc/def", false, SLASHLIT), // matchslash5
            Case("\\[", "[", true, BSESC), // matchbackslash1
            Case("\\?", "?", true, BSESC), // matchbackslash2
            Case("\\*", "*", true, BSESC), // matchbackslash3
            Case("\\[a-z]", "\\a", true, NOBSESC), // matchbackslash4
            Case("\\?", "\\a", true, NOBSESC), // matchbackslash5
            Case("\\*", "\\\\", true, NOBSESC), // matchbackslash6
            Case("\\a", "a", true, DEFAULT), // matchbackslash7
            Case("a*b*c", "abcd", false, DEFAULT), // matchnot1
            Case("abc*abc*abc", "abcabcabcabcabcabcabca", false, DEFAULT), // matchnot2
            Case("some/**/needle.txt", "some/other/notthis.txt", false, DEFAULT), // matchnot3
            Case("some/**/**/needle.txt", "some/other/notthis.txt", false, DEFAULT), // matchnot4
            Case("/**/test", "test", false, DEFAULT), // matchnot5
            Case("/**/test", "/one/notthis", false, DEFAULT), // matchnot6
            Case("/**/test", "/notthis", false, DEFAULT), // matchnot7
            Case("**/.*", "ab.c", false, DEFAULT), // matchnot8
            Case("**/.*", "abc/ab.c", false, DEFAULT), // matchnot9
            Case(".*/**", "a.bc", false, DEFAULT), // matchnot10
            Case(".*/**", "abc/a.bc", false, DEFAULT), // matchnot11
            Case("a[0-9]b", "a_b", false, DEFAULT), // matchnot12
            Case("a[!0-9]b", "a0b", false, DEFAULT), // matchnot13
            Case("a[!0-9]b", "a9b", false, DEFAULT), // matchnot14
            Case("[!-]", "-", false, DEFAULT), // matchnot15
            Case("*hello.txt", "hello.txt-and-then-some", false, DEFAULT), // matchnot16
            Case("*hello.txt", "goodbye.txt", false, DEFAULT), // matchnot17
            Case("*some/path/to/hello.txt", "some/path/to/hello.txt-and-then-some", false, DEFAULT), // matchnot18
            Case("*some/path/to/hello.txt", "some/other/path/to/hello.txt", false, DEFAULT), // matchnot19
            Case("a", "foo/a", false, DEFAULT), // matchnot20
            Case("./foo", "foo", false, DEFAULT), // matchnot21
            Case("**/foo", "foofoo", false, DEFAULT), // matchnot22
            Case("**/foo/bar", "foofoo/bar", false, DEFAULT), // matchnot23
            Case("/*.c", "mozilla-sha1/sha1.c", false, DEFAULT), // matchnot24
            Case("*.c", "mozilla-sha1/sha1.c", false, SLASHLIT), // matchnot25
            Case("**/m4/ltoptions.m4", "csharp/src/packages/repositories.config", false, SLASHLIT), // matchnot26
            Case("a[^0-9]b", "a0b", false, DEFAULT), // matchnot27
            Case("a[^0-9]b", "a9b", false, DEFAULT), // matchnot28
            Case("[^-]", "-", false, DEFAULT), // matchnot29
            Case("some/*/needle.txt", "some/needle.txt", false, DEFAULT), // matchnot30
            Case("some/*/needle.txt", "some/one/two/needle.txt", false, SLASHLIT), // matchrec31
            Case("some/*/needle.txt", "some/one/two/three/needle.txt", false, SLASHLIT), // matchrec32
            Case(".*/**", ".abc", false, DEFAULT), // matchrec33
            Case("foo/**", "foo", false, DEFAULT), // matchrec34
        )

    @Test fun matchesLikeGlobset() {
        val wrong =
            cases.filter { c ->
                val g = Glob.new(c.glob, c.options)
                g.isMatch(c.path) != c.matches || GlobSet(listOf(g)).isMatch(c.path) != c.matches
            }
        assertEquals(emptyList(), wrong)
    }
}
