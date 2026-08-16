-- OMS O7 D5 — driver settlement / remittance. Closes backlog B1.
--
-- Ahsan has no device (§6 D-5), so the signed paper invoices and the cash he hands back at day end are the
-- ONLY controls on the money. D4 built the record of what he collected; it posts nothing. This migration adds
-- the two things that turn that record into a control:
--
--   1. a REMITTANCE STATE on each collection — an un-remitted collection is cash the company believes a driver
--      is holding, and it must be visible and ageable rather than implied;
--   2. the SETTLEMENT itself — declared vs counted vs variance, on the same convention the till's Z report
--      already uses (`cashier_shift.variance` = counted − expected), because an admin who reads both screens
--      must not have to remember which way round each one is.
--
-- Idempotent and re-runnable throughout (standard D7).

-- ── 1. Who the collection belongs to, STAMPED ────────────────────────────────────────────────────────────
--
-- A remittance posts a receipt per collection, and a receipt needs a trade account. `orders.customer_id`
-- (V20/D2c) has it, but joining `orders` on every day-end read — and on a list that is read every working day
-- — is the derive-on-read shape this platform stamps instead. The name rides along for the same reason V19
-- duplicated `booked_by_name`: a settlement outlives the outlet's row being renamed or merged.
SET @sql := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='delivery_record' AND COLUMN_NAME='customer_id')=0,
    'ALTER TABLE delivery_record ADD COLUMN customer_id BIGINT NULL AFTER shipment_id', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='delivery_record' AND COLUMN_NAME='customer_name')=0,
    'ALTER TABLE delivery_record ADD COLUMN customer_name VARCHAR(255) NULL AFTER customer_id', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- BACKFILL, and it is safe to do here where V19's was not. `orders` is in THIS database, so no cross-service
-- call and no manual step on a fresh or a production deploy; and the value is not an inference — it is the
-- very column the dispatch already billed. Rows whose order never had a trade account (storefront) stay NULL,
-- which is the honest value, and the settlement refuses them by name rather than guessing.
UPDATE delivery_record dr
  JOIN orders o ON o.id = dr.order_id
   SET dr.customer_id   = o.customer_id,
       dr.customer_name = o.customer_name
 WHERE dr.customer_id IS NULL;

-- ── 2. The remittance state of a collection ──────────────────────────────────────────────────────────────
--
-- NULL settlement_id = OPEN: keyed as collected, not yet handed over and counted. That is the whole control
-- surface, and it is ONE column on purpose — a collection cannot belong to two settlements, so the once-only
-- guarantee is structural rather than a check somebody could forget to write.
SET @sql := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='delivery_record' AND COLUMN_NAME='settlement_id')=0,
    'ALTER TABLE delivery_record ADD COLUMN settlement_id BIGINT NULL AFTER credit_notes', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- The receipt business-service raised when this collection was remitted. Kept so the delivery, the invoice it
-- was collected against and the receipt that cleared it stay linked from either end.
SET @sql := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='delivery_record' AND COLUMN_NAME='receipt_no')=0,
    'ALTER TABLE delivery_record ADD COLUMN receipt_no VARCHAR(64) NULL AFTER settlement_id', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- D3b: scoping a read is not the same as indexing it. `idx_delivery_org` (V21) serves "this org's deliveries";
-- the day-end read is "this org's collections that are STILL OPEN, oldest first", which that index cannot
-- satisfy without a filesort over every delivery the tenant has ever keyed. Same lesson as D1's
-- idx_orders_org_status_created, which exists because the pending queue reads ASC while the back office reads DESC.
SET @sql := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='delivery_record' AND INDEX_NAME='idx_delivery_open')=0,
    'CREATE INDEX idx_delivery_open ON delivery_record (organization_id, settlement_id, recorded_at)', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ── 3. The settlement ────────────────────────────────────────────────────────────────────────────────────
--
-- There is deliberately NO status column and no draft state. A half-finished remittance would be a row that
-- claims custody of cash while posting none of it — strictly worse than no row, because the collections would
-- leave the open list without reaching the books. One act, one transaction, confirmed on creation.
--
--   declared_amount — what the DRIVER's own keyed entries add up to. Computed server-side from the claimed
--                     rows, never accepted from the client (OMS-5's lesson: the channel says what happened,
--                     the server says what it is worth).
--   counted_amount  — what was physically in the bag.
--   variance_amount — counted − declared. NEGATIVE is short. Same sign convention as cashier_shift.variance,
--                     and like it, RECORDED AND REPORTED BUT NOT JOURNALLED: there is no cash-with-drivers
--                     clearing account on this platform, and inventing one here would be a second money path.
--   settled_by_*    — stamped, per V19's rule. A settlement outlives the staff who made it, and "who signed
--                     off a short bag" must still be answerable after they leave.
--   driver_name     — free text, inherited from delivery_record.delivered_by, which D4 defined as a NOTE and
--                     not an identity. A settlement may not mix two drivers; the service refuses it.
CREATE TABLE IF NOT EXISTS driver_settlement (
  id                 BIGINT        NOT NULL AUTO_INCREMENT,
  organization_id    BIGINT        DEFAULT NULL,
  settlement_seq     BIGINT        NOT NULL,
  settlement_no      VARCHAR(32)   DEFAULT NULL,
  driver_name        VARCHAR(255)  DEFAULT NULL,
  settlement_date    DATE          DEFAULT NULL,
  declared_amount    DECIMAL(19,2) DEFAULT NULL,
  counted_amount     DECIMAL(19,2) DEFAULT NULL,
  variance_amount    DECIMAL(19,2) DEFAULT NULL,
  collection_count   INT           DEFAULT NULL,
  deposit_reference  VARCHAR(120)  DEFAULT NULL,
  note               VARCHAR(500)  DEFAULT NULL,
  settled_by_user_id BIGINT        DEFAULT NULL,
  settled_by_name    VARCHAR(255)  DEFAULT NULL,
  settled_at         datetime(6)   DEFAULT NULL,
  PRIMARY KEY (id),
  -- Per-org numbering, MAX+1 inside the creating transaction. The UNIQUE key is what makes that safe against a
  -- concurrent settlement — a read-then-write check alone loses the race. Exactly the SHP- recipe (V15).
  UNIQUE KEY uk_driver_settlement_org_seq (organization_id, settlement_seq),
  KEY idx_driver_settlement_org_date (organization_id, settlement_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
