-- SRA-3 (2026-10-15) — the sales rep the OFFICE chose when it keyed a rep's hand-written order.
-- Design: microservices/docs/slices/sra-sales-by-rep-and-area.md §6.
--
-- A rep who books signed in needs nothing here: their login is already on the order (booked_by_user_id), and
-- business-service credits the rep linked to that login when the order is invoiced. This column is only for the
-- paper order, where the person keying it is not the rep. The rep is checked against the business's own list when the
-- order is invoiced (business-service owns the list), and the invoice stamps the rep's name then.

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'orders' AND COLUMN_NAME = 'rep_id') = 0,
    'ALTER TABLE orders ADD COLUMN rep_id BIGINT NULL',
    'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
