-- B2B Phase 4a — the stamped credit account: which customer row's limit and pooled balance govern this account.
--
-- Shared pool: a company sets ONE limit and its branches draw on it. Exposure is SUM(due_amount) over everyone
-- pointing at the same credit account, compared against the limit on the row pointed to.
--
-- Why a stamped column and not a join to party-service's hierarchy: the credit check runs on the sell path, and
-- resolving the hierarchy there would put a cross-service hop on the hottest path in the POS. The hierarchy is
-- edited rarely; the stamp is rewritten then (see CustomerAccountService.restampSubtree).
--
-- The BACKFILL is mandatory, not cosmetic. A NULL credit account would make the group SUM match no rows, so the
-- first credit check after deploy would read a zero balance and wave through every sale. `id -> id` makes every
-- existing customer its own single-member group, which is arithmetically identical to the pre-4a behaviour.

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='customer' AND COLUMN_NAME='credit_account_customer_id')=0,
    'ALTER TABLE customer ADD COLUMN credit_account_customer_id BIGINT NULL', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- Every existing customer becomes its own credit account. Re-runnable: only fills what is still NULL, so it can
-- never re-flatten a hierarchy that has already been built.
UPDATE customer SET credit_account_customer_id = customer_id WHERE credit_account_customer_id IS NULL;

-- The group SUM runs on the sell path — index it. (organization_id first: every read here is tenant-scoped.)
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='customer' AND INDEX_NAME='idx_customer_org_credit_account')=0,
    'CREATE INDEX idx_customer_org_credit_account ON customer (organization_id, credit_account_customer_id)', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
