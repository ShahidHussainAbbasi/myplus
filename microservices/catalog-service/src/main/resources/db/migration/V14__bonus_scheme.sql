-- Bonus / free-goods schemes (task #17 P1). Design: microservices/docs/slices/bonus-schemes.md
--
-- ONE table for three kinds of offer — what a SUPPLIER gives us, what we give a CUSTOMER, and what we give a
-- customer TYPE — because they are the same shape with different keys, exactly as price_rule is one table for
-- three kinds of price rule. Splitting them would triple the work to answer one question ("what bonus applies
-- to this line?") and would guarantee the three drift apart.
--
-- It lives in catalog-service beside price_rule for the same reason price_rule does: an offer is a property of
-- the CATALOG, not of one channel, so POS, storefront and purchasing get identical answers by construction.
-- vendor_id is an opaque identifier here — Vender lives in business-service — which is the precedent
-- price_rule.customer_id already sets (Customer lives there too).
--
-- WHY EVERY COLUMN EARNS ITS PLACE:
--
--   bonus_type          INCLUSIVE (10 delivered, 9 billed) vs EXCLUSIVE (11 delivered, 10 billed). Without
--                       it "10+1" cannot be interpreted for stock, invoice, cost or tax. SAP models exactly
--                       this distinction and FMCG/pharma distribution trades on both. NOT NULL, deliberately.
--   qualification_mode  ONE_TIME ("buy 10 get 1" — 15 paid still earns 1) vs REPEATING ("every 10 get 1" —
--                       15 paid earns 1, and "every 5 get 1" earns 3). Required because the partial-return
--                       clawback (D7) recomputes entitlement from the RETAINED paid quantity, and that sum
--                       has no single answer without this field.
--   reward_product_id   The reward may be a DIFFERENT product ("buy a machine, get a coffee pack"). This is
--                       what a bare bonus QUANTITY cannot express, and the reason Sell.bonus_quantity could
--                       not simply be reused.
--   stackable           Whether two matching schemes may combine. Default 0: rules resolve to ONE winner,
--                       matching how price_rule already behaves, because silently combining offers gives away
--                       stock nobody approved.
--   status              DRAFT / ACTIVE / EXPIRED / DISABLED. A scheme gives away goods, so it needs a
--                       governance state, not just an `active` bit.
--
-- ALL MODULES ARE LIVE, so this is purely additive: a new table, nothing altered. An org with no rows behaves
-- exactly as it does today.
--
-- Idempotent (D7): CREATE TABLE IF NOT EXISTS + information_schema-guarded indexes, so a re-run — or a dev
-- database where ddl-auto already created the table from the entity — is a no-op.

CREATE TABLE IF NOT EXISTS bonus_scheme (
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    organization_id     BIGINT        DEFAULT NULL,
    user_id             BIGINT        DEFAULT NULL,

    -- Owner-facing identity, for traceability on a receipt and in a report ("SUP-UNILEVER-OIL-10-1").
    code                VARCHAR(64)   NOT NULL,

    -- WHO the offer belongs to: VENDOR (a supplier's offer to us), CUSTOMER (a named account),
    -- or CUSTOMER_TYPE (a tier — WALK_IN / RETAILER / WHOLESALE / VIP).
    scope               VARCHAR(16)   NOT NULL,
    vendor_id           BIGINT        DEFAULT NULL,   -- scope = VENDOR   (opaque; owned by business-service)
    customer_id         BIGINT        DEFAULT NULL,   -- scope = CUSTOMER (opaque; owned by business-service)
    customer_type       VARCHAR(16)   DEFAULT NULL,   -- scope = CUSTOMER_TYPE

    -- WHAT triggers it, and WHAT is given. Both may be a product or a whole category.
    trigger_target      VARCHAR(16)   NOT NULL DEFAULT 'PRODUCT',
    trigger_product_id  BIGINT        DEFAULT NULL,
    trigger_category_id BIGINT        DEFAULT NULL,
    -- NULL reward_product_id means "same product as the trigger", which is the common case and must not
    -- require repeating the id.
    reward_product_id   BIGINT        DEFAULT NULL,

    -- THE ARITHMETIC. paid_quantity is the threshold; bonus_quantity is what is issued free.
    paid_quantity       DECIMAL(19,3) NOT NULL,
    bonus_quantity      DECIMAL(19,3) NOT NULL,
    bonus_type          VARCHAR(16)   NOT NULL,   -- INCLUSIVE | EXCLUSIVE
    qualification_mode  VARCHAR(16)   NOT NULL,   -- ONE_TIME  | REPEATING

    -- Governance and resolution.
    priority            INT           NOT NULL DEFAULT 0,
    stackable           BIT(1)        DEFAULT b'0',
    status              VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE',
    -- Both bounds INCLUSIVE. NULL/NULL = always live, the common case.
    starts_on           DATE          DEFAULT NULL,
    ends_on             DATE          DEFAULT NULL,

    created_at          DATETIME      DEFAULT NULL,
    updated_at          DATETIME      DEFAULT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- The resolver asks "every live scheme for this org" once and picks a winner in memory — the same shape
-- price_rule uses — so the index that matters is the tenant predicate plus status.
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='bonus_scheme' AND INDEX_NAME='idx_bonus_scheme_org_status')=0,
    'CREATE INDEX idx_bonus_scheme_org_status ON bonus_scheme (organization_id, status)', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='bonus_scheme' AND INDEX_NAME='idx_bonus_scheme_org_vendor')=0,
    'CREATE INDEX idx_bonus_scheme_org_vendor ON bonus_scheme (organization_id, vendor_id)', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='bonus_scheme' AND INDEX_NAME='idx_bonus_scheme_org_trigger')=0,
    'CREATE INDEX idx_bonus_scheme_org_trigger ON bonus_scheme (organization_id, trigger_product_id)', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- A scheme CODE is how an operator refers to the offer, so it must be unique within a tenant — two live
-- "SUP-OIL-10-1" rules would make "which one applied?" unanswerable on a receipt.
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='bonus_scheme' AND INDEX_NAME='uk_bonus_scheme_org_code')=0,
    'CREATE UNIQUE INDEX uk_bonus_scheme_org_code ON bonus_scheme (organization_id, code)', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
