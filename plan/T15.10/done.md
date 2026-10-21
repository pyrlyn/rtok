**T15.10 the two surfaces cannot drift** · T15.0 · `tests/surface_parity.rs` (new)
Do: enumerate the pages each surface exposes and assert the sets are equal, so a page added to one
fails the build until it exists on the other. This is D23's gate, and it replaces the prose promise.
Check: adding a page to `rtok web` alone fails `just check` with the page's name in the message.
Status: done 2026-09-09 · Model: GLM-5.3-Flash (zai-coding-plan)
Check result: tests/surface_parity.rs owns the page table T15.0 deferred — model::pages() returns
[("overview", "usage"), ("plugins", "plugins")], and web_serves_exactly_the_pages_the_model_offers
holds the web surface to it by parsing the frame rtok::web::frame sends on /ws (each side's set comes
from its own source: the model's declaration vs. the wire's keys). cargo test --test surface_parity
1 passed; fmt and clippy -D warnings clean, in a detached worktree at 5975877. The gate is real:
adding a calls field to Snapshot alone fails with "assertion left == right failed: a page exists on
one surface and not the other (D23) … right: ["calls", "overview", "plugins"]" — the page's name in
the message — and the suite is inside just check's cargo test. rtok tui does not exist yet; its tabs
plug into the same compare at T15.1+, and T15.12 extends the test to reading commands.
