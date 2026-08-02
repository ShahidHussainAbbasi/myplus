-- Why a sale line was priced the way it was (slice b2b-P2 = OMS B1 = requirement #10).
--
-- The whole justification for contract/tier pricing is that today a trade customer's price is a number a
-- cashier typed, and NOTHING records why — which is exactly what makes a disputed invoice unanswerable.
-- Resolving the price without persisting its reason would rebuild that same problem with extra steps, so
-- the reason is stored on the line beside the price it explains.
--
-- Deliberately the human string ("Wholesale price -12%") and not just the rule id: a rule can later be
-- edited or deleted, and an invoice must still explain itself years afterwards. The rule id alone would
-- become a dangling pointer to a rate nobody can reconstruct. This is a SNAPSHOT, exactly like
-- sell.catalog_price and sell.cost_price beside it.
--
-- ALL MODULES ARE LIVE: additive, nullable, no back-fill. NULL means "priced at catalog" (or a legacy sale),
-- which is every existing row.
--
-- Idempotent (D7): guarded on information_schema.

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='sell' AND COLUMN_NAME='price_reason')=0,
    'ALTER TABLE sell ADD COLUMN price_reason VARCHAR(64) DEFAULT NULL', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
