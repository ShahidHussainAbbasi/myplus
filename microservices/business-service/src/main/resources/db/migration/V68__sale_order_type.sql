-- RST-R2a — how a sale was served: eaten in, taken away, or delivered.
--
-- Design: microservices/docs/slices/rst-r2a-order-types.md
--
-- ⚠ NULLABLE, NO DEFAULT, AND DELIBERATELY NOT BACK-FILLED.
--
-- NULL is a real answer here, not a missing one: it means "this tenant does not work in service modes".
-- Every invoice that already exists — in every tenant, restaurant or not — stays NULL, and every screen
-- must render that as ABSENT rather than choosing something for it.
--
-- Seeding a default would be the actual damage. 'DINE_IN' on existing rows retro-labels a year of retail
-- and pharmacy sales as dine-in; 'TAKE_AWAY' does the same in the other direction. Either one puts numbers
-- into a report that nobody entered, and the day the owner splits their takings by service mode they would
-- be reading fiction about every month before this deploy. The Cypress gate asserts the absence (case 2)
-- precisely because a default is the tempting, wrong convenience.
--
-- VARCHAR(16), NOT a MySQL ENUM. CustomerHistory.orderType is a Java enum mapped @Enumerated(EnumType.STRING),
-- and a String field against a MySQL ENUM column is one of the two shapes that crash-looped two services
-- under ddl-auto=validate (59 and 9 restarts). The symptom is not a warning — it is a service that does not
-- start, and every screen behind it then fails for an unrelated-looking reason. 16 fits the longest value
-- ('TAKE_AWAY', 9) with room for a future one.
--
-- Guarded ADD COLUMN (the V35/V54/V65 pattern in this module): a re-run on a database that already has the
-- column does nothing, so this is safe on every environment including one restored from an older dump, and
-- a fresh deploy needs no manual step.

SET @s := (SELECT IF(EXISTS(SELECT 1 FROM information_schema.columns
                            WHERE table_schema = DATABASE() AND table_name = 'customer_history'
                              AND column_name = 'order_type'),
                    'DO 0',
                    'ALTER TABLE customer_history ADD COLUMN order_type VARCHAR(16) DEFAULT NULL'));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- The day's takings split by service mode is the reason this column exists, and it is a per-tenant, per-day
-- read: WHERE organization_id = ? AND dated BETWEEN ? AND ?, GROUP BY order_type. Without an index that is a
-- scan of every invoice the tenant has ever raised, on a screen the owner opens at close of business.
--
-- Leading with organization_id keeps it usable by the tenant-scoped reads that do not mention order_type at
-- all, and the trailing column lets the grouping be satisfied from the index. Rows with a NULL order_type
-- are indexed too, which is what makes "how many sales have no service mode" cheap rather than a scan.
--
-- ⚠ DEPLOY NOTE — customer_history is MyISAM (verified, not assumed: information_schema.tables says so on
-- this dev database, and it is a known deviation recorded against DOC-INT). Two consequences for whoever
-- runs this against a large tenant:
--   * MyISAM takes a TABLE-LEVEL LOCK while building an index, so writes to customer_history block for the
--     duration. On a shop's live database that is the till pausing. Run it in a maintenance window, or
--     convert the table to InnoDB first — which is a separate, larger decision with its own preflight.
--   * There is no transactional DDL, so a failure here leaves the column added and the index absent. Both
--     statements are guarded by information_schema, so re-running finishes the job rather than erroring.
-- Key length is not a concern: bigint + datetime + varchar(16) is ~77 bytes against MyISAM's 1000-byte limit.
SET @s := (SELECT IF(EXISTS(SELECT 1 FROM information_schema.statistics
                            WHERE table_schema = DATABASE() AND table_name = 'customer_history'
                              AND index_name = 'idx_ch_org_dated_order_type'),
                    'DO 0',
                    'CREATE INDEX idx_ch_org_dated_order_type ON customer_history (organization_id, dated, order_type)'));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
