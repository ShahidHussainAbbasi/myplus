-- Store credit (SF-5 Model B): the GL outbox persists the event as individual columns, so it needs a store_credit
-- column to carry the store-credit split (redeemed on SALE / issued on SALE_RETURN) to finance (GL account 2200).
-- Without it the field was dropped in transit → finance posted the refund to Cash instead of the Store-Credit liability.
-- Idempotent (dev ddl-auto:update also adds it): guarded ADD COLUMN.

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='gl_outbox' AND COLUMN_NAME='store_credit')=0,
    'ALTER TABLE gl_outbox ADD COLUMN store_credit decimal(19,2) DEFAULT NULL', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
