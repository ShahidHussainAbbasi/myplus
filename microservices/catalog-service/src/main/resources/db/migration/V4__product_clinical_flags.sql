-- Pharmacy rx enforcement (review B1): the two clinical flags the SELL hot path needs move onto the product
-- master, so SagaSellService can read them off the ProductRef it already fetches per line — no cross-service
-- call at checkout. Same shape as multi-rate tax resolving ProductRef.taxRate from catalog.
--
-- pharma-service's medicine_clinical keeps the richer clinical layer (drug category) and keeps owning
-- drug_interactions; it stops being the source of truth for THESE two flags (one writer — see
-- docs/pharmacy-rx-enforcement-design.md D1/D2). Existing pharma flags are copied over by the one-time
-- backfill endpoint, because the two tables live in different databases.
--
-- NOT NULL DEFAULT FALSE: a product with no clinical opinion is an ordinary product, and the sell guard must
-- never have to reason about null. Idempotent (dev ddl-auto:update may already have added them): guarded ADD.

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='products' AND COLUMN_NAME='rx_required')=0,
    'ALTER TABLE products ADD COLUMN rx_required BOOLEAN NOT NULL DEFAULT FALSE', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='products' AND COLUMN_NAME='controlled_substance')=0,
    'ALTER TABLE products ADD COLUMN controlled_substance BOOLEAN NOT NULL DEFAULT FALSE', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
