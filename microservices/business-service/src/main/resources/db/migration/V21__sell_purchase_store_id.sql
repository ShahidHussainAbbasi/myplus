-- Multi-location P2b: tag sales & purchases with the store they occurred at. Nullable — legacy rows stay
-- NULL and remain visible to their creator (drain as re-saved). Reads become store-aware only when the
-- caller has store grants (empty grants => no store filter => unchanged single-store behaviour).
ALTER TABLE sell     ADD COLUMN store_id BIGINT NULL;
ALTER TABLE purchase ADD COLUMN store_id BIGINT NULL;
CREATE INDEX idx_sell_store     ON sell (store_id);
CREATE INDEX idx_purchase_store ON purchase (store_id);
