# `rtok web` access token (audit S2)

## Problem

`rtok web` serves `/ws`, which can change config, apply `doctor --fix` and clear junk. The
socket was reachable without any credential by every process on the machine, and by any host
once `web.host` is bound beyond loopback.

## Design

- **Issue on start.** `rtok web` generates a 256-bit token from the OS CSPRNG (two UUID v4
  values, hex, 64 chars) and writes it to `<RTOK_HOME>/web-token` (default `~/.rtok/web-token`)
  before it prints its startup line.
- **Owner-only file.** Unix: created with mode `0600` atomically (`create_new` + `mode`, then
  renamed into place), so it is never readable by group/other, not even briefly. Windows: the
  file's inheritance is removed and only the creating user is granted access (`icacls`); if that
  fails the server does not start.
- **Fresh per start.** Every start replaces the file with a new token; the previous token stops
  working immediately. There is no TTL beyond the process lifetime.
- **Presenting the token.** CLI and other non-browser clients send
  `Authorization: Bearer <token>` on the WebSocket upgrade. Browsers cannot set headers on a
  WebSocket, so the SPA uses `?token=<token>` on the `/ws` URL as a fallback. The SPA takes the
  token from the page URL once (`http://127.0.0.1:3333/?token=…`), keeps it in `sessionStorage`
  and removes it from the address bar. The header wins if both are present.
- **Check.** Constant-time comparison (`subtle`). A missing or wrong token gets `401` before the
  `Origin` check and before the upgrade.
- **Remote hosts.** There is no file on another machine, so no token: remote clients stay out.
  The non-loopback bind refusal is unchanged: `web.host` beyond loopback still needs
  `RTOK_WEB_ALLOW_REMOTE=1`. (With that opt-in the page and `/health` are still served to the
  network; `/ws` still requires the token, which a remote host cannot read.)
- **Not covered.** `/health` and the static SPA stay open (they carry no state-changing
  capability). Any process running as the same user can read the file; that is the trust
  boundary, as for `~/.ssh`. The token travels in a URL query for browsers only, so keep it out of
  shared logs and screenshots.

## Using it

```sh
rtok web                                   # writes ~/.rtok/web-token
open "http://127.0.0.1:3333/?token=$(cat ~/.rtok/web-token)"
```

## Tests

`tests/web_token.rs` runs the real binary: file created `0600` (Unix) / ACL names only the
current user (Windows); a client with the file's token connects; no or wrong token gets 401;
a restart changes the token and the old one fails; a non-loopback bind is refused without
`RTOK_WEB_ALLOW_REMOTE=1`; another OS user cannot open the file (real open as `nobody` when run
as root, otherwise skipped with a message and the mode bits asserted instead).
