# Prompt modes helpers (`rtok.modes`)

**How it works.** `compressProse(text, intensity)` strips English fluff deterministically:
fenced code blocks (CommonMark rules: ``` or ~~~, ≤3 spaces indent, closer at least as long)
and backtick spans are copied verbatim; outside them it drops leading pleasantries (all
intensities), filler words and articles (Full/Ultra), and "and then / and also / as well as"
(Ultra). Negations (`not`, `never`, `no`, `only`, `except`) and words glued to names/paths
are never dropped. `evaluateLadder` climbs the YAGNI ladder
(skip → reuse → stdlib → native → dep → one-liner → minimum; `mustNotSimplify` forces Minimum).

**Enable / disable.** Library helpers, used by benches/hooks; no config switch (same as Rust).
**Test.** `./gradlew :rtok:macosArm64Test` (CompressTest, LadderTest).
**Limitations.** Kotlin strings are UTF-16; byte-level checks from Rust are mapped to char
checks (`code >= 0x80` for non-ASCII), which gives the same results for the ported tests.
