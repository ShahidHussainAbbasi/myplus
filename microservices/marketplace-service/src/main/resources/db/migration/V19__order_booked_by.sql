-- OMS O7 D2 — WHO booked this order.
--
-- D1 built the review phase but attributed nothing: an order arrived in the queue with no way to say which rep
-- took it. That blocks three things a distributor needs — telling a booker which of their orders were rejected
-- (the founding requirement asks for exactly this), measuring coverage per rep, and paying commission.
--
-- TWO columns, not one, and the second is the point:
--   booked_by_user_id — the identity, for filtering "my orders" and for joining to a rep.
--   booked_by_name    — stamped at write, never resolved at read.
--
-- The name is duplicated ON PURPOSE. An order is a commercial record that outlives its staff: a rep leaves,
-- their user row is deleted or renamed, and every order they ever took would otherwise show a blank or the
-- wrong person. The same rule CustomerHistory.booked_by_name and order_amendment.user_name already follow.

SET @sql := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='orders' AND COLUMN_NAME='booked_by_user_id')=0,
    'ALTER TABLE orders ADD COLUMN booked_by_user_id BIGINT NULL AFTER user_id', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='orders' AND COLUMN_NAME='booked_by_name')=0,
    'ALTER TABLE orders ADD COLUMN booked_by_name VARCHAR(255) NULL AFTER booked_by_user_id', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- "My orders, newest first" is the booker's home screen and the only query this column adds, so it is indexed
-- for exactly that shape (D3b: index the predicate the query runs, not the one the table was first indexed
-- for). Org first, because every read on this platform is org-scoped before it is anything else.
SET @sql := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='orders' AND INDEX_NAME='idx_orders_org_booker_created')=0,
    'CREATE INDEX idx_orders_org_booker_created ON orders (organization_id, booked_by_user_id, created_at)', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- No backfill. Every order that exists predates order booking entirely, so there is no honest value to write:
-- stamping them with anyone would invent an attribution, and NULL correctly reads as "not booked by a rep".
-- Same call O1 made for LEGACY_UNPOSTED and O5d for shipment_line.verified.
