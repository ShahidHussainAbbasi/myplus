-- Customer classification — finishes a field the codebase already half-declares (slice b2b-P0).
--
-- `CustomerType` (WALK_IN / RETAILER / WHOLESALE / VIP) already exists as an enum and is already declared
-- on CustomerDTO; only the entity field was commented out, so nothing ever persisted. This migration adds
-- the column so the existing enum finally works, and the B2B/B2C channel is DERIVED from it
-- (CustomerType.isB2B) rather than stored twice. One field, one source of truth.
--
-- B2B/B2C describes WHO THE SHOP SELLS TO, not who the logged-in user is — so it belongs on the customer,
-- resolved per transaction, never on the JWT. See microservices/docs/b2b-b2c-rollout-plan.md §3b.
--
-- ALL MODULES ARE LIVE, so this is strictly additive:
--   * new NULLABLE column — no rewrite of existing rows at ALTER time
--   * back-filled to 'WALK_IN', whose channel is B2C = exactly today's behaviour for every existing shop
--   * VARCHAR(16), deliberately NOT a MySQL native enum: @Enumerated(STRING) onto a native enum means
--     every new value later needs ALTER … MODIFY (ddl-auto will not do it — it fails with "Data
--     truncated"). VARCHAR keeps adding GOVT/NGO a code change instead of a migration.
--
-- Idempotent (D7): every statement is guarded on information_schema, so a re-run — or a database that
-- already has the column — is a no-op.

-- 1. The column.
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='customer' AND COLUMN_NAME='customer_type')=0,
    'ALTER TABLE customer ADD COLUMN customer_type VARCHAR(16) DEFAULT NULL', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- 2. Back-fill. Every customer that exists today is a walk-in until someone says otherwise. Leaving NULL
--    would make the sell path branch on "unknown" — a third state nobody designed for.
UPDATE customer SET customer_type = 'WALK_IN' WHERE customer_type IS NULL;

-- 3. Index (D3). "Show me my trade accounts" filters on this beside the standard tenant predicate, so it
--    is indexed WITH organization_id rather than alone.
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='customer' AND INDEX_NAME='idx_customer_org_type')=0,
    'CREATE INDEX idx_customer_org_type ON customer (organization_id, customer_type)', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
