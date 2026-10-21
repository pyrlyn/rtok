Split from T520. No documented config path for MiMo Desktop (early access); a third-party README (https://github.com/akaradje/mimo-desktop, unverified) says Desktop reads `~/.config/mimocode/mimocode.jsonc`. Waited for the creator's run of the read-only probe `scratchpad/mimo-desktop-probe/run.sh`; done = a Desktop variant (detection plus the same config family or its own) with tests.

Check: `rtok agents list` shows a MiMo Desktop variant detected from its app, install writes the config the app reads, tests cover it; `just check`.

Result (2026-10-11): the probe was replaced by a read-only look at the vendor's own shipped app on the creator's machine (`/Applications/Xiaomi MiMo AI.app` 26.1010.101546, bundle id `com.xiaomi.mimo.desktop-ai`). Its `app.asar` holds the config resolver `MIMOCODE_HOME/config`, else `XDG_CONFIG_HOME/mimocode`, else `~/.config/mimocode`, with the files `mimocode.jsonc`, `mimocode.json`, `config.json`: the CLI's own config family (cited with the vendor docs in `research.md` section 15). So `mimo` gets a second variant, `MiMo Desktop` (`Kind::Desktop`, apps `/Applications/Xiaomi MiMo AI.app` and `~/Applications/...`), detection only: both variants install the same `mcp.rtok` and plugin link, as Kilo's do. Windows and Linux install paths of the app are unverified and not probed. Test: `the_desktop_variant_is_detected_from_its_app_and_installs_the_cli_files`; `docs/agents.md` regenerated.

Status: done 2026-10-11
Model: Claude Code / sonnet-5.5
