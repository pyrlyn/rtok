# rtok vs caveman v4.0.1 (github.com/JuliusBrussee/caveman)

caveman's headline saving, the terse "caveman" reply style, is a prompt/skill. It changes what a model
writes and needs a live model, so it can't be measured deterministically and is not benchmarked here.
Its deterministic tool is `caveman-shrink` (built from the v4.0.1 tag with Go 1.26.9). It shrinks MCP tool
catalogs: it drops examples and titles and shortens long descriptions, but keeps names, params and enums.

Measured with `bench/c14/run_b2_catalog.py`. The catalogs are the `tools/list` result of real servers in
the image (lean-ctx 3.11.4 in its default profile, engram 3.4.0) plus caveman's own test catalog,
serialised compactly. The rtok number is the catalog as seen through `rtok mcp -- <server>`. caveman-shrink
ran 3 times with a fresh `HOME` and `CAVEMAN_CCR_DB` and gave identical output each time. Tokens: tiktoken `o200k_base`.

| Catalog | Tools | Raw | rtok (`rtok mcp -- <server>`) | caveman-shrink | caveman saves |
|---|---:|---:|---:|---:|---:|
| lean-ctx | 12 | 2513 | 2513 | 2207 | 12.2 % |
| engram | 23 | 5822 | 5822 | 5213 | 10.5 % |
| caveman testdata | 2 | 279 | — | 197 | 29.4 % |

Verdict: rtok's MCP wrapper passes foreign catalogs through unchanged (by design: "wrap losslessly"), so
caveman-shrink wins by 10–29 %. Its description cut is lossy, and its README says so.

What rtok needs: an opt-in catalog compaction in `rtok mcp --` (drop `title`/`examples`, cap long
descriptions, keep the schema structure). Not done yet.
