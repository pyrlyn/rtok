**T18.3 install in one line** · T18.1 · `README.md`
Do: the README Install section leads with the shell installer for macOS and Linux and the Homebrew tap, with the source build kept below for contributors. State plainly that the binary is unsigned.
Check: `just readme-check` green; the installer line, pasted into a clean bash and a clean zsh on macOS, exits 0, puts `rtok` on `PATH` and prints the released version.
Status: done 2026-09-04
Model: Opus 5
Check result: `just readme-check` green — the new fences carry no `# check` marker, so nothing
tries to install from a release that does not exist yet. The section now leads with the dist
shell installer (`releases/latest/download/rtok-installer.sh`, a URL that survives every release)
and `brew install pyrlyn/tap/rtok`, mentions `rtok-update`, states that the binaries are unsigned
and that a browser download is quarantined by macOS while the installer is not, and keeps the
source build below for contributors. `just check` green (12 test binaries).
Not verified: the clause "pasted into a clean bash and a clean zsh on macOS, exits 0" cannot run
until a release exists — `releases/latest/download/` 404s on a repo with no releases. The
installer dist generates is POSIX `sh` and is piped to `sh`, so the caller's shell does not matter;
that is a reading of the artifact, not a run of it.
