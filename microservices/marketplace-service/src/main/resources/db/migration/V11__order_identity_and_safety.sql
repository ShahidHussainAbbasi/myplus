-- OMS O2 — order identity, idempotency and concurrency safety.
--
-- Three defects, one table:
--   OMS-8  public tracking resolved a RAW AUTO-INCREMENT id, unscoped. Guessable (the id space could be walked
--          across tenants) and useless to quote to a customer. Fixed by a per-org SO- series.
--   OMS-3  the ORDER row was not idempotent. O1 made the SALE idempotent on the cart token, so a double-submit
--          replayed ONE invoice but inserted TWO orders — picked and shipped twice.
--   OMS-4  no optimistic locking. Several people touch one order in a day; last write silently won.
--
-- order_seq mirrors invoice_seq (slice 22), credit_note_seq (3c) and quote_seq (4b): the UNIQUE constraint is
-- what makes MAX+1 allocation safe under concurrency.
--
-- BACKFILL is mandatory, not cosmetic. Existing orders have no number, and tracking resolves by number after
-- this slice — without it every historical order becomes untrackable and any emailed link dies. Numbers are
-- assigned per org in id order, so the sequence matches the order the shop actually took them.
--
-- Idempotent: every step is guarded on information_schema, so re-running is a no-op.

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='orders' AND COLUMN_NAME='order_seq')=0,
    'ALTER TABLE orders ADD COLUMN order_seq BIGINT NULL', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='orders' AND COLUMN_NAME='order_no')=0,
    'ALTER TABLE orders ADD COLUMN order_no VARCHAR(32) NULL', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='orders' AND COLUMN_NAME='idempotency_key')=0,
    'ALTER TABLE orders ADD COLUMN idempotency_key VARCHAR(100) NULL', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- version starts at 0, not NULL: Hibernate treats a null @Version as a transient instance and would try to
-- INSERT an existing row on the first update of a pre-O2 order.
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='orders' AND COLUMN_NAME='version')=0,
    'ALTER TABLE orders ADD COLUMN version BIGINT NOT NULL DEFAULT 0', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- Backfill: number every existing order per org, in id order. ROW_NUMBER needs MySQL 8 (already required).
UPDATE orders o
JOIN (
    SELECT id,
           ROW_NUMBER() OVER (PARTITION BY organization_id ORDER BY id) AS seq
    FROM orders
    WHERE order_seq IS NULL
) n ON n.id = o.id
SET o.order_seq = n.seq,
    o.order_no  = CONCAT('SO-', LPAD(n.seq, 6, '0'))
WHERE o.order_seq IS NULL;

-- Constraints come AFTER the backfill, or the unique index would trip over the NULLs it is meant to prevent.
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='orders' AND INDEX_NAME='uq_order_org_seq')=0,
    'ALTER TABLE orders ADD CONSTRAINT uq_order_org_seq UNIQUE (organization_id, order_seq)', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- The idempotency guarantee itself. NULLs do not collide in MySQL, so pre-O2 rows (and any order placed without
-- a key) are unaffected while every keyed placement is deduplicated.
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='orders' AND INDEX_NAME='uq_order_org_idem')=0,
    'ALTER TABLE orders ADD CONSTRAINT uq_order_org_idem UNIQUE (organization_id, idempotency_key)', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- Tracking resolves by (order_no, contact); index the lookup it now performs on every public track request.
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='orders' AND INDEX_NAME='idx_orders_order_no')=0,
    'CREATE INDEX idx_orders_order_no ON orders (order_no)', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
