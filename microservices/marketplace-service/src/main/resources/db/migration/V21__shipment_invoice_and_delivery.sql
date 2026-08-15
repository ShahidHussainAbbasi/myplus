-- OMS O7 D4 — what a parcel was invoiced as, and what of it actually arrived.
--
-- ── 1. shipment.invoice_no — the limitation D1 recorded and parked for this slice ────────────────────────
--
-- Under ON_DISPATCH every parcel raises its OWN invoice, but the number was written only to `orders`, one
-- column, overwritten by each dispatch. So a part-delivered order carried its LAST parcel's invoice and
-- nothing else, and `processReturn` could only ever reverse that one — the earlier parcels' revenue stayed
-- booked with no way to reach it.
--
-- An invoice now corresponds one-for-one to a parcel, so it belongs on the parcel. `orders.invoice_no` stays:
-- it is what POS and storefront orders (invoiced at placement, one invoice for the whole order) use, and what
-- the O1 reconciliation read keys on.
SET @sql := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='shipment' AND COLUMN_NAME='invoice_no')=0,
    'ALTER TABLE shipment ADD COLUMN invoice_no VARCHAR(64) NULL AFTER shipment_no', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ── 2. What actually arrived, per line ───────────────────────────────────────────────────────────────────
--
-- A shop taking 8 of 10, or refusing a short-expiry batch outright, is ROUTINE in pharma distribution (design
-- finding B2). Without somewhere to record it the driver either fails the whole delivery or misreports it.
--
-- NULL means "not yet keyed" and is deliberately distinct from 0 ("keyed, and they took none"): the first is
-- an outstanding job for the admin, the second is a completed one with a full credit note behind it. Collapsing
-- them would make a delivery nobody has processed indistinguishable from a total rejection.
SET @sql := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='shipment_line' AND COLUMN_NAME='delivered_quantity')=0,
    'ALTER TABLE shipment_line ADD COLUMN delivered_quantity INT NULL AFTER quantity', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ── 3. The delivery record itself ────────────────────────────────────────────────────────────────────────
--
-- Ahsan has NO DEVICE (§6 D-5). The signed paper invoice IS the proof of delivery; this is the keyed summary
-- of it, and the columns are named so nobody can later mistake one for the other:
--
--   recorded_at   — when the ADMIN KEYED it, which may be hours after the goods arrived. There is deliberately
--                   no `delivered_at`: the system did not observe that moment and must not invent it.
--   recorded_by_* — who KEYED it, stamped at write. NOT the driver: attributing a keyed entry to the person
--                   who was at the door would record an observation as though it came from someone who did
--                   not make it (§3.3).
--   delivered_by  — free text naming the driver, because that IS worth knowing; it is a note, not an identity.
CREATE TABLE IF NOT EXISTS delivery_record (
  id                 BIGINT       NOT NULL AUTO_INCREMENT,
  organization_id    BIGINT       DEFAULT NULL,
  order_id           BIGINT       NOT NULL,
  shipment_id        BIGINT       DEFAULT NULL,
  invoice_no         VARCHAR(64)  DEFAULT NULL,
  outcome            VARCHAR(24)  DEFAULT NULL,   -- DELIVERED | PARTIAL | REFUSED
  settlement         VARCHAR(24)  DEFAULT NULL,   -- PAID | PART_PAID | CREDIT
  amount_collected   DECIMAL(19,2) DEFAULT NULL,
  credit_notes       VARCHAR(500) DEFAULT NULL,   -- CRN- numbers raised for the shortfall
  delivered_by       VARCHAR(255) DEFAULT NULL,   -- the driver, as a NOTE
  recorded_by_user_id BIGINT      DEFAULT NULL,
  recorded_by_name   VARCHAR(255) DEFAULT NULL,
  note               VARCHAR(500) DEFAULT NULL,
  recorded_at        datetime(6)  DEFAULT NULL,
  PRIMARY KEY (id),
  KEY idx_delivery_order (order_id, recorded_at),
  KEY idx_delivery_org (organization_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
