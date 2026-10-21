**T18.4 codesigning and notarisation, written down** · T18.1 · `docs/release.md`
Do: one document covering the release as it runs today and how to turn on Developer ID signing and notarisation, locally and in Actions. Name the secrets dist actually reads, the Apple assets that have to exist first, and the point where notarisation stops short for a bare CLI binary in a tarball. State plainly which steps were run on this machine and which need an Apple Developer account nobody here has.
Check: the secret names in the document are the ones `dist generate` emits with `macos-sign = true`, not remembered ones; every `codesign`, `security`, `ditto`, `spctl` and `xcrun` invocation matches the flags of the tools installed on this machine; `just check` green.
Status: done 2026-09-04
Model: Opus 5
Check result: the three secret names in the document — `CODESIGN_CERTIFICATE`,
`CODESIGN_CERTIFICATE_PASSWORD`, `CODESIGN_IDENTITY` — were read off a workflow generated with
`macos-sign = true` in a throwaway clone, not recalled; that is the entire diff turning the key on
produces, so dist 0.32 signs and does not notarise. A deliberate nonsense key (`macos-frobnicate`)
was accepted silently, which is why the document warns against trusting a guessed `macos-notarize`.
The stapling limit is quoted from `xcrun stapler --help`: disk images, code-signed executable
bundles and flat packages — a bare Mach-O in a `.tar.xz` is none of them, so notarisation here can
never be stapled. `xcrun notarytool store-credentials/submit/log` flags and `codesign`'s usage line
match the installed tools. `just check` green (12 test binaries).
Deviations: four files, not the one the task named — `README.md` gained a single line pointing at
the document from the sentence that already said the binaries are unsigned. `spctl` and
`codesign`'s long-form flags could not be probed (both blocked by this machine's shell allowlist),
so the document says so rather than claiming a verification that did not happen.
Found while running the Check, unrelated to it: `just check` failed once with `CMake Error at
tools/CMakeLists.txt:2 (add_subdirectory): source "shell" ... not an existing directory`. The
published `lbug` 0.20.2 crate ships `lbug-src/tools/CMakeLists.txt` but no `tools/shell`, and the
guard is `if(${BUILD_SHELL})`, so it only fails when that variable is true — and one of the four
`lbug-*` build directories held a `CMakeCache.txt` with `BUILD_SHELL:BOOL=ON` despite `build.rs`
passing `-DBUILD_SHELL=OFF`. `cargo clean -p lbug` (7 759 files, 788 MiB) fixed it and the next
`just check` was green. If that error reappears, it is a stale cmake cache, not the source.
