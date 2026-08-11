-- OMS O7 D1 — the approval phase, and the record of who changed what.
--
-- Everything O1–O5e built starts at "an order exists and is ready to pack". A distributor's order does not:
-- an order booker takes it at the shop, and the warehouse admin reviews, amends, and confirms or rejects it
-- before anything is picked. That phase had no representation at all — `NEW` already means "accepted".
--
-- Three additions, in the order they are needed:
--   1. two new lifecycle states, in front of the existing ones;
--   2. the rejection REASON, without which a rejected order tells the booker nothing;
--   3. the amendment trail, which becomes mandatory the moment TWO people may edit one order (D-2).

-- 1) The ENUM. fulfilment_status is a real MySQL ENUM, so a Java constant alone fails at runtime with
--    "Data truncated for column 'fulfilment_status'" — the same ALTER V7, V15 and V16 each had to take.
--    Guarded on the COLUMN_TYPE so re-running is a no-op (D7).
SET @sql := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='orders'
                  AND COLUMN_NAME='fulfilment_status' AND COLUMN_TYPE LIKE '%PENDING_APPROVAL%')=0,
    'ALTER TABLE orders MODIFY fulfilment_status enum(''PENDING_APPROVAL'',''REJECTED'',''NEW'',''PACKED'',''BACKORDERED'',''PARTIALLY_SHIPPED'',''SHIPPED'',''DELIVERED'',''CANCELLED'',''RETURN_REQUESTED'',''RETURNED'') DEFAULT NULL',
    'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 2) Why it was rejected.
--
-- A rejection with no reason is unusable: the booker cannot fix the order, and cannot explain it to the shop.
-- Nullable because every historical row predates rejection entirely — there is no honest value to backfill.
SET @sql := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='orders' AND COLUMN_NAME='rejection_reason')=0,
    'ALTER TABLE orders ADD COLUMN rejection_reason VARCHAR(500) NULL AFTER books_status', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 3) The amendment trail.
--
-- D-2 settled that BOTH the booker and the admin may revise a rejected order. With one editor you could infer
-- who changed what from the order's own history; with two you cannot, and "who dropped the price on this
-- order?" becomes unanswerable — on the one document whose price the warehouse is now allowed to change (D-3).
--
-- One row per amendment EVENT, not per field: an amendment is a single act of judgement ("cut the line, drop
-- the price, move the date") and splitting it across rows would lose that it was one decision. `changes` holds
-- the field-level before/after as JSON.
--
-- No FK to orders: this is an audit record. It must survive its order, and a constraint that could block a
-- write is the wrong trade for a trail whose whole purpose is to still be there afterwards.
CREATE TABLE IF NOT EXISTS order_amendment (
  id                BIGINT       NOT NULL AUTO_INCREMENT,
  order_id          BIGINT       NOT NULL,
  organization_id   BIGINT       DEFAULT NULL,
  user_id           BIGINT       DEFAULT NULL,
  user_name         VARCHAR(255) DEFAULT NULL,   -- stamped at write; never resolved at read (the user may be gone)
  summary           VARCHAR(500) DEFAULT NULL,   -- human-readable one-liner for the timeline
  changes           TEXT         DEFAULT NULL,   -- JSON: [{field, from, to}]
  reason            VARCHAR(500) DEFAULT NULL,
  created_at        datetime(6)  DEFAULT NULL,
  PRIMARY KEY (id),
  -- The read is always "the amendments for THIS order, oldest first", and it is org-scoped like every other
  -- read on the platform (D3).
  KEY idx_order_amendment_order (order_id, created_at),
  KEY idx_order_amendment_org (organization_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 4) The pending queue is a WORKING LIST, read constantly by the admin and ordered oldest-first, so it gets its
--    own index rather than riding idx_orders_org_created (which is DESC by created_at for the back-office list).
--    D3b: index the predicate the query actually runs, not the one the table was first indexed for.
SET @sql := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='orders' AND INDEX_NAME='idx_orders_org_status_created')=0,
    'CREATE INDEX idx_orders_org_status_created ON orders (organization_id, fulfilment_status, created_at)', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
