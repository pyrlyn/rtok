# Agent notes — `toon`

**Owns** `src/plugins/toon/**`.

**Contract**: the `Plugin` trait, `Ctx` and the host capabilities come from the published
`rtok-plugin-sdk` crate (`crates/rtok-plugin-sdk`), not from `crate::plugin` — import them as
`rtok_plugin_sdk::…`.

**Invariants**
- Lossless: encoding must round-trip; keep a decode function and a property test for it.
- Only arrays of ≥ `min_rows` objects with identical scalar-valued keys are encoded.
- Original JSON is archived first; the encoded block prints the 8-character archive id (`rtok expand` resolves it). The measurement `ref_id` stays the full sha. A decision already stored is replayed as written.
- `default_on` is `true` since T127. A user file that still sets `enabled = false` pinned the old default; `rtok doctor` notes the key and leaves the file alone.

**Checks**: `plan/` T11.7. Order: `roadmap.md` § `toon`.
