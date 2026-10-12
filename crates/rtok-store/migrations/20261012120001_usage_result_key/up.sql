-- T583.1: the provider-scoped identity of the Batch result a `usage` row was read from
-- (`anthropic/<batch id>/<custom_id>`), so fetching the same results file again, over a
-- restart or from two proxies at once, still counts each billed request once. NULL for every
-- row written before this column and for rows that are not Batch results; a UNIQUE index
-- keeps NULLs distinct, so those rows stay valid and are never matched to a result.
ALTER TABLE usage ADD COLUMN result_key TEXT;
CREATE UNIQUE INDEX usage_result_key ON usage (result_key);
