-- Supplier bonus on goods-in (task #17 P2). Design: microservices/docs/slices/bonus-schemes.md
--
-- THE DEFECT THIS CLOSES, on the purchase side:
-- a distributor's "buy 10, get 1 free" delivers ELEVEN bottles against an invoice for ten. Until now the
-- purchase recorded only the billed quantity, so stock rose by 10 while the shelf gained 11 — a one-unit
-- divergence on every bonus delivery, which never self-corrects and compounds.
--
-- WHY A COLUMN ON purchase RATHER THAN A DERIVED FIGURE:
-- the bonus is a FACT OF THE DELIVERY, not a re-computation of the supplier's current offer. A scheme can be
-- edited or expire tomorrow; what physically arrived today must not change when it does. Stamp at write —
-- the same rule that puts last-purchase rates on the product master rather than deriving them on read.
--
-- WHY paid_total AS WELL AS THE RATE:
-- cost must be allocated across the units RECEIVED, and the obvious "5000 / 11 = 454.54" then multiplied back
-- gives 4,999.94 — six paisa that reconciles to nothing. Storing what was actually PAID lets consumption
-- allocate exactly, instead of rounding a per-unit figure and hoping the pieces add up. Nullable because
-- every historical row predates it, and a NULL there means "fall back to rate x quantity", which is exactly
-- what those rows already meant.
--
-- ALL MODULES ARE LIVE, so this is additive only: two nullable columns, no change to existing behaviour. A
-- purchase with no bonus behaves byte-for-byte as it does today.
--
-- Idempotent: information_schema-guarded ADD COLUMN, so a re-run — or a dev database where ddl-auto already
-- added the column from the entity — is a no-op.

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='purchase' AND COLUMN_NAME='bonus_quantity')=0,
    'ALTER TABLE purchase ADD COLUMN bonus_quantity FLOAT DEFAULT NULL', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='purchase' AND COLUMN_NAME='paid_total')=0,
    'ALTER TABLE purchase ADD COLUMN paid_total DECIMAL(19,2) DEFAULT NULL', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- The scheme that produced the bonus, for traceability on a receipt and in a report. An opaque reference:
-- bonus_scheme lives in catalog-service, and this column must never become a foreign key across that seam.
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='purchase' AND COLUMN_NAME='bonus_scheme_code')=0,
    'ALTER TABLE purchase ADD COLUMN bonus_scheme_code VARCHAR(64) DEFAULT NULL', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
