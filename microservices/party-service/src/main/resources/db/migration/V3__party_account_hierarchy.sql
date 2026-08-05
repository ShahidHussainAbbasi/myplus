-- B2B Phase 4a — account hierarchy (company → branch → contact) on the shared identity master.
--
-- The hierarchy lives HERE, not in business-service, because it is identity STRUCTURE — the same shape Education
-- corporate sponsors and Welfare corporate donors already need. The credit and AR that hang off it stay in the
-- module that owns them (business-service stamps its own `customer.credit_account_customer_id`).
--
-- No hard FK on parent_party_id, matching this service's existing style (party_role_link.party_id is the same).
-- The invariants that actually matter — same-org parent, no cycles, max depth 3, INDIVIDUAL is never in a tree —
-- are enforced server-side on write in PartyService, because none of them is expressible as a column constraint.
--
-- account_level defaults to INDIVIDUAL so every pre-existing party is correctly described as "not in any group",
-- and nothing changes for a shop that never builds a hierarchy.
--
-- Idempotent: each ADD is guarded on information_schema, so re-running is a no-op.

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='party' AND COLUMN_NAME='parent_party_id')=0,
    'ALTER TABLE party ADD COLUMN parent_party_id BIGINT NULL', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='party' AND COLUMN_NAME='account_level')=0,
    'ALTER TABLE party ADD COLUMN account_level VARCHAR(12) NOT NULL DEFAULT ''INDIVIDUAL''', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- Children-of lookup: reading a company's branches, and the cycle/depth walk on every hierarchy write.
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='party' AND INDEX_NAME='idx_party_org_parent')=0,
    'CREATE INDEX idx_party_org_parent ON party (organization_id, parent_party_id)', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
