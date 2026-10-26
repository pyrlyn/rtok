# TOML reading

- **How it works:** config code uses only `:toml` (`rtok.toml.TomlReader` / `TomlDocument` / `TomlValues`).
  The backend is the `:toml-ktoml` adapter over [ktoml](https://github.com/orchestr7/ktoml) 0.7.1, chosen in one
  place: `rtok-config/.../TomlBackend.kt`. Swap the backend by writing another adapter and changing that line.
- **Line numbers:** taken from ktoml's `TomlNode.lineNo` (validation errors stay `path:line`).
- **Adapter fixes over ktoml:** duplicate keys are rejected (ktoml silently keeps the last); multi-line string
  spans are computed by the adapter (ktoml only reports the opening line).
- **Test:** `./gradlew :toml-ktoml:macosArm64Test :rtok-config:macosArm64Test`.
- **Limitations:** parse error text is ktoml's (`Line N: ...`), not Rust `toml`'s wording.
  `rtok config set` still uses the existing line editor `TomlEdit`/`TomlWriter` (Validate.kt), because ktoml
  cannot rewrite a file while keeping comments. Decision pending: keep it, or drop comment preservation.
