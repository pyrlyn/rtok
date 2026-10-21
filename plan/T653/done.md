A semantic-cache hit kept a `usage` row, and `otel_token_totals` added it to the provider-billed counters even though no upstream request was made. Totals now skip a call that carries a `semantic_cache_hit` measurement. The hit's own usage row stays, so per-request analytics still see it. `docs/otel.md` (and `docs/ru`, `docs/uk`) say the provider totals omit those hits.

Landed in `b1c2d7fb` (#1050). This card records that the check already holds on main.

Check: a hit keeps its request usage but does not increase provider-billed totals; a live response still does.

Check result: `token_totals_count_the_upstream_request_not_the_cache_hit_served_after_it` inserts two usage rows of input 100 / output 25 and counts only the live call. `proxy_cache_hit_records_usage_and_request_bytes` keeps both usage rows and asserts provider-billed totals are `(10, 2)`, the upstream request alone.

Status: done 2026-10-19
