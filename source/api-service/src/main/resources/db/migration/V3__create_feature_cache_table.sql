-- V3: Create feature_cache table
CREATE TABLE feature_cache (
    key         TEXT PRIMARY KEY,
    value       JSONB NOT NULL,
    ttl_expire  TIMESTAMPTZ
);

-- Index for TTL cleanup
CREATE INDEX idx_feature_cache_ttl ON feature_cache(ttl_expire) WHERE ttl_expire IS NOT NULL;
