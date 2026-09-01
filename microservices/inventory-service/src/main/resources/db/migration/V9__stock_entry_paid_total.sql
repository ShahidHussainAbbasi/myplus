-- The exact amount paid for a batch (task #17 P2). Design: microservices/docs/slices/bonus-schemes.md
--
-- WHY A BATCH NEEDS A TOTAL AND NOT JUST A UNIT PRICE:
-- stock_entries.purchase_price is per unit, which is exact only while cost = rate x quantity. A supplier
-- bonus breaks that: "buy 10, get 1 free" puts 11 units in the batch for 5,000, so the unit cost is
-- 454.5454... and ANY stored per-unit figure is a rounding of it.
--
-- The consequence is not cosmetic. Consuming 6 of those 11 units gives:
--     rounded unit cost:  6 x 454.54            = 2,727.24
--     allocated from total: 5,000 x 6 / 11      = 2,727.27
-- and the batch eventually closes having expensed 4,999.94 of a 5,000 purchase. Three paisa here, six there,
-- belonging to nothing and reconciling to nothing.
--
-- Storing what was actually PAID lets consumption allocate exactly. It is the same rule the installment work
-- already follows: a total is ALLOCATED, never derived by rounding a proportion and multiplying back.
--
-- NULLABLE, deliberately: every batch received before this predates the field, and NULL there means
-- "purchase_price x quantity" — which is exactly what those batches have always meant, and remains exact for
-- them because no bonus was involved.
--
-- ALL MODULES ARE LIVE, so this is additive only: one nullable column, nothing altered, no behaviour change
-- for a delivery without a bonus.
--
-- Idempotent: information_schema-guarded ADD COLUMN, so a re-run — or a dev database where ddl-auto already
-- added the column from the entity — is a no-op.

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='stock_entries' AND COLUMN_NAME='paid_total')=0,
    'ALTER TABLE stock_entries ADD COLUMN paid_total DECIMAL(19,2) DEFAULT NULL', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
