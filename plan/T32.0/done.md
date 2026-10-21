**T32.0 design/survey: WASM host** · — · `crates/rtok-plugin-sdk/PLAN.md` or `docs/plugin-authoring.md` extension
Do: survey WASM runtimes suitable for a static Rust binary (at least three). Define the `from_plugins` load path, the Measurement example contract, and what stays in-process for in-tree plugins. D6 call-out: no vendored third-party plugins in this repo. No implementation.
Check: PLAN (`crates/rtok-plugin-sdk/PLAN.md`): Wasmtime / Wasmi / Wasm3; chosen Wasmi; WASM not on hook; Gate P32 `wasm-demo` `Measurement`.
Complexity: 3/5
Status: done 2026-09-11 · Model: Composer 2.5
