# Token estimator (`rtok.estimate`)

**How it works.** `ceil(chars / charsPerToken)` per class (Code 3.5, Prose 4.2, Json 3.0, Cjk 1.0
from `[estimator]`); chars are Unicode code points like Rust `chars()`. Not a billed count.
**Enable / disable.** Always on; rates configurable via `[estimator]` once config layering lands (phase 2).
**Test.** `./gradlew :rtok:macosArm64Test` (TokensTest).
**Limitations.** `stats --calibrate` is a stub returning `skipped`, as in Rust.
