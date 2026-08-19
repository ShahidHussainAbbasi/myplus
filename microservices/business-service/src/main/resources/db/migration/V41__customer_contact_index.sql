-- Slice I1 (CSV import) — the index behind the import's batched duplicate check.
--
-- D3/D3b: index the predicate the query actually RUNS, not the one the table was first indexed for.
-- customer is already scoped by organization_id, but the import asks
--     WHERE contact IN (…) AND (organization_id = ? OR (organization_id IS NULL AND user_id = ?))
-- which the existing scoped indexes do not serve. Shipped WITH the method that needs it, not after.
--
-- ── WHY contact(64) AND NOT contact ────────────────────────────────────────────────────────────────────
-- `customer` is MyISAM (verified against the live schema, not inferred: information_schema.tables reports
-- ENGINE=MyISAM), and MyISAM's maximum key length is 1000 BYTES. `contact` is varchar(255) utf8mb4 = 1020
-- bytes on its own, so (organization_id, contact) is 1028 bytes and MySQL refuses it outright:
--
--     ERROR 1071 (42000): Specified key was too long; max key length is 1000 bytes
--
-- The first attempt at this migration did exactly that and crash-looped the service. Worth recording why it
-- was not obvious: every other index on this table is (organization_id, <bigint>) — org_user, org_type,
-- org_rep, org_credit_account — so this is the FIRST index on `customer` to carry a wide varchar, and the
-- limit had never been reached before. InnoDB would have accepted it (3072-byte limit with DYNAMIC row
-- format), which is precisely why reasoning about it from the query alone did not surface the problem.
--
-- A 64-character prefix costs 8 + 64*4 = 264 bytes and still serves the query: MySQL uses the prefix to
-- narrow an equality/IN lookup and then rechecks the full value from the row, so the batched duplicate
-- check still avoids the full scan it exists to replace. 64 characters is far beyond any real phone number
-- or contact string, so in practice the prefix is unique anyway.
--
-- DO NOT "tidy" the (64) away. It is load-bearing until `customer` is InnoDB.
--
-- ── WHY NOT UNIQUE ─────────────────────────────────────────────────────────────────────────────────────
-- Existing tenants may already hold duplicate contacts — the registration screen's duplicate check is on
-- NAME, and only among the creator's own rows — so a UNIQUE index would fail this migration on live data.
-- The import enforces uniqueness in application code and reports what it skipped.
--
-- Guarded because MySQL has no CREATE INDEX IF NOT EXISTS: re-running against a database that already has
-- the index must not fail startup. (FlywayConfig.repairThenMigrate clears the failed marker from the first
-- attempt before this runs, so no manual DELETE from flyway_schema_history is needed.)

SET @idx := (SELECT COUNT(*) FROM information_schema.statistics
             WHERE table_schema = DATABASE()
               AND table_name = 'customer'
               AND index_name = 'idx_customer_org_contact');

SET @sql := IF(@idx = 0,
    'CREATE INDEX idx_customer_org_contact ON customer (organization_id, contact(64))',
    'DO 0');

PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
