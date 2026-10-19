-- T583: the identity of one Batch result line (provider-scoped results location plus the
-- request's id, hashed), so fetching the same results file again records each result once.
-- NULL for every other usage row and for rows written before this column existed; a partial
-- unique index leaves those alone while two inserts of one result can never both land.
ALTER TABLE usage ADD COLUMN result_key TEXT;
CREATE UNIQUE INDEX usage_result_key ON usage (result_key) WHERE result_key IS NOT NULL;
