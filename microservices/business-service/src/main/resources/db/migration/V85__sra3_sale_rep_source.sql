-- SRA-3 (2026-10-15) — every invoice records WHO booked it (the sales rep) and HOW it came in.
-- Design: microservices/docs/slices/sra-sales-by-rep-and-area.md §6.
--
--   rep_id / rep_name  the rep who took the order from the customer — STAMPED at write (the name too), so a sale stays
--                      credited to the rep who made it when the rep is renamed, deactivated or the outlet moves.
--                      NULL = no rep (a counter sale, a portal order), or a sale made before this ("Not recorded").
--   sale_source        COUNTER · REP_LOGIN (the rep booked it signed in) · REP_PAPER (the office keyed a rep's paper
--                      order) · OFFICE (the office keyed an order with no rep) · PORTAL (the customer ordered online).
--
-- `booked_by_name` is unchanged: it is who KEYED the sale (printed as "Booked By"). Reports run on the REP.
-- No backfill: an old invoice never knew its rep, and guessing would be worse than "Not recorded".

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'customer_history' AND COLUMN_NAME = 'rep_id') = 0,
    'ALTER TABLE customer_history ADD COLUMN rep_id BIGINT NULL, ADD COLUMN rep_name VARCHAR(120) NULL, ADD COLUMN sale_source VARCHAR(16) NULL',
    'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- SRA-4 groups and filters sales by rep inside a period.
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'customer_history' AND INDEX_NAME = 'idx_ch_org_rep_dated') = 0,
    'CREATE INDEX idx_ch_org_rep_dated ON customer_history (organization_id, rep_id, dated)',
    'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
