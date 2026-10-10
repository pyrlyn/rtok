-- T385.12.2: the tier a provider says it served a proxied request on (OpenAI `service_tier`,
-- Anthropic `usage.service_tier`), read from the response. NULL when the response named none
-- and for rows written before this column existed.
ALTER TABLE calls ADD COLUMN service_tier TEXT;
