-- Credit limit + payment terms (slice b2b-P1 = OMS B4 = customer requirement #9).
--
-- `Customer.dueAmount` and `Vender.dueAmount` already record what IS owed, maintained by recomputeDue /
-- recomputePayable. Nothing anywhere records what the shop is WILLING to be owed, so a balance could grow
-- without limit and the owner found out from the ageing report a month later. These three columns are that
-- missing number.
--
-- The check is deliberately LOCAL: exposure = dueAmount + this transaction's unpaid portion, compared
-- against the limit on the row already loaded. No finance-service call on the sell path (see
-- microservices/docs/b2b-b2c-rollout-plan.md and b2b-shared-library-review.md — the RULES are shared via
-- the common-credit library, the DATA stays here).
--
-- ALL MODULES ARE LIVE, so this is strictly additive and deliberately NOT back-filled:
--   * new NULLABLE columns — no rewrite of existing rows at ALTER time
--   * NULL means "no limit", which is exactly today's behaviour. A back-fill would be actively wrong here:
--     inventing a number for every existing customer would start warning shopkeepers about limits nobody
--     set. Contrast V29, where back-filling WALK_IN was right because "no type" needed a definite meaning.
--   * DECIMAL(19,2) — the currency type used platform-wide; never a float for money.
--
-- No index: these columns are read from the customer/vendor row the caller already holds, never filtered on.
--
-- Idempotent (D7): every statement is guarded on information_schema, so a re-run — or a database that
-- already has the columns — is a no-op.

-- 1. How much this customer may owe us. NULL = no limit = no check.
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='customer' AND COLUMN_NAME='credit_limit')=0,
    'ALTER TABLE customer ADD COLUMN credit_limit DECIMAL(19,2) DEFAULT NULL', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- 2. Net payment terms in days (Net 30 / Net 60). NULL = no terms; the due date stays hand-entered exactly
--    as it is today. This is what finally makes the EXISTING ageing report meaningful for trade accounts —
--    buckets are only as good as the due dates feeding them.
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='customer' AND COLUMN_NAME='payment_terms_days')=0,
    'ALTER TABLE customer ADD COLUMN payment_terms_days INT DEFAULT NULL', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- 3. The supplier side of requirement #9 ("dues limit customer/supplier"): how much WE are willing to owe
--    this vendor. Same semantics, opposite direction — vender.due_amount is a payable, not a receivable.
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='vender' AND COLUMN_NAME='credit_limit')=0,
    'ALTER TABLE vender ADD COLUMN credit_limit DECIMAL(19,2) DEFAULT NULL', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
