# Installing rtok

Every way to install rtok, plus shell completions and man pages. The short path is in the [README](../README.md#install); first steps are in [getting-started.md](getting-started.md).

## Install

macOS on Apple silicon, and Linux x86-64. The script is POSIX `sh`, so it behaves the
same whether your shell is bash or zsh; it puts `rtok` in `~/.cargo/bin`.

```bash
curl --proto '=https' --tlsv1.2 -LsSf https://github.com/pyrlyn/rtok/releases/latest/download/rtok-installer.sh | sh
```

Or with [ketch](https://github.com/pyrlyn/ketch) — it installs straight from these
GitHub Release archives, no tap or formula:

```bash
curl -fsSL https://raw.githubusercontent.com/pyrlyn/ketch/main/install.sh | bash
ketch install pyrlyn/rtok
```

Or Homebrew (once the formula PR in [pyrlyn/homebrew-tap](https://github.com/pyrlyn/homebrew-tap)
has been merged for that release):

```bash
brew install pyrlyn/tap/rtok
```

Or from npm or PyPI, which carry the same native binary (macOS arm64, Linux x86-64 glibc,
Windows x86-64); no Node or Python code runs once it is installed. Both are published by hand,
so check the registry for the version you want ([docs/release.md](release.md#npm-pypi-and-cratesio)):

```bash
npm i -g rtok-cli            # or: npx rtok-cli --help
uv tool install rtok-cli     # or: pipx install rtok-cli, uvx --from rtok-cli rtok --help
```

The package is `rtok-cli` on both registries; the command it installs is `rtok` (npm also
links `rtok-cli`).
Prefer a global install over `npx`/`uvx` before `rtok agents install`: hosts call `rtok` from
`PATH`, and a cached one-off copy can disappear.

The dist installer also gives you `rtok-update`; run it to move to the newest release.

macOS release binaries are codesigned with the Developer ID (notarisation is still off). A
browser download of a `.tar.xz` may still quarantine the file until Gatekeeper has checked it;
the installer above and `ketch install pyrlyn/rtok` do not set quarantine. Details:
[docs/release.md](release.md).

Building from source works on any platform Rust supports:

```bash
git clone https://github.com/pyrlyn/rtok && cd rtok
mise install
mise exec -- cargo install --path .
rtok --version
```

Shell completions and the man page are generated from the same clap tree as
`--help`, so they never drift from the CLI surface:

```bash
rtok completions bash > ~/.bash_completion.d/rtok   # or zsh, fish, powershell, elvish
rtok completions clink > %LOCALAPPDATA%\clink\rtok.lua  # cmd.exe through Clink
rtok completions --install                           # to $SHELL's own directory; --uninstall undoes it
rtok completions                                     # in a terminal: pick the shells, checked = installed
rtok completions --list                              # shell, installed yes/no, file; for scripts
rtok man | man -l -                                  # or save as manpath/rtok.1
rtok man --dir ~/.local/share/man/man1               # rtok.1 plus rtok-<command>.1 for every subcommand
```

The picker lists every shell with its completions pre-checked when they are installed.
Space toggles, Enter installs the newly checked shells and removes the unchecked ones,
Esc or Ctrl-C changes nothing. Without a terminal, `rtok completions` with no shell fails
instead of waiting; use `--list` to read the state.

Release archives also carry them pre-built in `share/`: `share/man/man1/*.1` and
`share/completions/` (`rtok.bash`, `_rtok`, `rtok.fish`, `rtok.ps1`, `rtok.elv`, `rtok.lua`).

A shorter path (install → doctor → hooks) is also in
[`docs/getting-started.md`](getting-started.md).
