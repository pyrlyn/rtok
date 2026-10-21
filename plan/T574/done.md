Confirmed through the actual Axum SPA router with both directory and embedded assets: [serve](src/web/spa.rs#L198) passes uri.path() directly to lookup, preserving percent escapes as literal filename text. A present assets/logo space.svg is requested as /assets/logo%20space.svg and returns 404; the same happens for filenames containing a hash, a percent sign or Unicode. An ordinary assets/plain.svg control returns 200. Neither [directory lookup](src/web/spa.rs#L117) nor embedded lookup decodes the name. T310.9 covers both sources but has no fix for encoded asset names.

Execution plan:

1. Add directory/embedded router fixtures for space, hash, percent and Unicode filenames, requesting their percent-encoded URLs and asserting the original bytes and MIME type.
2. Decode the URL path exactly once before filename lookup and route classification, then validate the decoded relative path with the existing confinement rules. Handle malformed escapes/invalid text explicitly rather than silently changing a filename.
3. Cover encoded traversal and separators, literal percent sequences without double decoding, missing assets, page fallback and negotiated compressed variants; retain the existing GET/HEAD behavior.

Check: encoded URLs resolve the intended existing asset in both sources, traversal remains rejected and unknown asset URLs stay 404, and `just check` passes. Actual router probes returned 404 for all four encoded-name fixtures and 200 for the plain-name control; HEAD controls correctly returned no body.

Result: SPA asset lookup decodes each URL path segment once and answers 400 for malformed escapes, non-UTF-8 and encoded separators, so `%20`/`%23`/`%25`/Unicode names resolve. New direct dep `percent-encoding` (already in the lock).

Check: `percent_encoded_asset_urls_resolve_the_file_they_name`.

Status: done 2026-10-12
Model: Claude Code / claude-sonnet-5-5
