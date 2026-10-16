-- MKT-4b — a supplier's dispatch deadline (R20.5 "SLA"). When a supplier accepts a part of supplier stock, the part
-- must leave within 24 hours: dispatch_by is stamped at Accept. The sweeper settles each one once its deadline
-- passes: DUE → ON_TIME (sent by then), LATE (not sent by then; the shopper is told MaxTheService is chasing it), or
-- VOID (the part or its store order ended first). NULL for every other part, which has no dispatch deadline.
-- slice: docs/slices/mkt-4b-supplier-dispatch.md
--
-- VARCHAR(12) for String (as acceptance_status), ddl-auto=validate. Idempotent (D7).

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mkt_seller_order' AND COLUMN_NAME = 'dispatch_by');
SET @s := IF(@c = 0, 'ALTER TABLE mkt_seller_order ADD COLUMN dispatch_by DATETIME NULL', 'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mkt_seller_order' AND COLUMN_NAME = 'dispatch_status');
SET @s := IF(@c = 0, 'ALTER TABLE mkt_seller_order ADD COLUMN dispatch_status VARCHAR(12) NULL', 'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- The sweeper's read: DUE parts past their deadline, oldest first.
SET @c := (SELECT COUNT(*) FROM information_schema.STATISTICS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mkt_seller_order' AND INDEX_NAME = 'idx_mkt_so_dispatch');
SET @s := IF(@c = 0, 'CREATE INDEX idx_mkt_so_dispatch ON mkt_seller_order (dispatch_status, dispatch_by)', 'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
