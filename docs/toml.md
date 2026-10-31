# TOML reading

- **How it works:** config code uses only `pyrlyn.kit:toml` from kotlin-kit (`toml.TomlReader` / `TomlDocument` / `TomlValues`).
  The backend is the `:toml-ktoml` adapter over [ktoml](https://github.com/orchestr7/ktoml) 0.7.1, chosen in one
  place: `rtok-config/.../TomlBackend.kt`. Swap the backend by writing another adapter and changing that line.
- **Line numbers:** taken from ktoml's `TomlNode.lineNo` (validation errors stay `path:line`).
- **Writing:** `TomlReader.write(tree)` encodes a JSON tree through ktoml's encoder (`KtomlWriter`). Used by
  `config set` and layered-value validation.
- **Adapter fixes over ktoml:** duplicate keys are rejected (ktoml silently keeps the last).
- **Test:** `./gradlew :toml-ktoml:macosArm64Test :rtok-config:macosArm64Test`.
- **Limitations:** parse error text is ktoml's (`Line N: ...`), not Rust `toml`'s wording.
  Writing drops all comments and formatting, and emits empty `[table]` headers for empty tables; so
  `rtok config set` does not preserve comments (differs from Rust's toml_edit).
