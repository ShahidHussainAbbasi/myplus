-- Contract & tiered pricing (slice b2b-P2 = OMS B1 = customer requirement #10).
--
-- Today a product has exactly ONE price (products.selling_price), so the only way to charge a trade customer
-- their agreed rate is for the cashier to type it in per line, from memory. That is unauditable — nothing
-- records WHY this customer paid 92 — and it makes the P0 margin guard fire on typos meant as discounts.
--
-- ONE table for all three kinds of rule (customer x product, customer x category, type x product|category):
-- they are the same shape with different keys, and splitting them would triple the join work to answer a
-- single question ("what does this customer pay for this product?").
--
-- Rules NEVER stack: the resolver (commerce-pricing PriceResolver) picks the single most specific live rule.
-- Precedence and tie-breaks live in that library, not in SQL, so POS / storefront / pharmacy get identical
-- answers by construction.
--
-- ALL MODULES ARE LIVE, so this is purely additive: a brand-new table, no change to products. An org with no
-- rows prices exactly as it does today (the resolver falls back to the catalog price).
--
-- Idempotent (D7): CREATE TABLE IF NOT EXISTS + information_schema-guarded index creation, so a re-run — or a
-- dev database where ddl-auto already created the table from the entity — is a no-op.

CREATE TABLE IF NOT EXISTS price_rule (
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    organization_id  BIGINT        DEFAULT NULL,
    user_id          BIGINT        DEFAULT NULL,
    -- WHO: CUSTOMER (a named account) or TYPE (a tier — Phase 0's Customer.customerType)
    scope            VARCHAR(16)   NOT NULL,
    customer_id      BIGINT        DEFAULT NULL,   -- set when scope = CUSTOMER
    customer_type    VARCHAR(16)   DEFAULT NULL,   -- set when scope = TYPE (WALK_IN/RETAILER/WHOLESALE/VIP)
    -- WHAT: a single product, or a whole category
    target           VARCHAR(16)   NOT NULL,
    product_id       BIGINT        DEFAULT NULL,   -- set when target = PRODUCT
    category_id      BIGINT        DEFAULT NULL,   -- set when target = CATEGORY
    -- HOW: an absolute unit price, or a percentage off the catalog price.
    -- FIXED 0 is a REAL price (a giveaway) and must not be read as "unset".
    mode             VARCHAR(16)   NOT NULL,
    value            DECIMAL(19,2) NOT NULL,
    -- Owner's explicit tie-break between two equally specific rules. Higher wins.
    priority         INT           NOT NULL DEFAULT 0,
    active           BIT(1)        DEFAULT b'1',
    -- Both bounds INCLUSIVE. NULL/NULL = always live, which is the common case and must not require typing
    -- two dates to express.
    starts_on        DATE          DEFAULT NULL,
    ends_on          DATE          DEFAULT NULL,
    created_at       DATETIME      DEFAULT NULL,
    updated_at       DATETIME      DEFAULT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- The quote endpoint asks "every live rule for this org" once per sale and resolves in memory, so the index
-- that matters is the tenant predicate plus the discriminators — NOT one index per rule kind.
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='price_rule' AND INDEX_NAME='idx_price_rule_org_active')=0,
    'CREATE INDEX idx_price_rule_org_active ON price_rule (organization_id, active)', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='price_rule' AND INDEX_NAME='idx_price_rule_org_product')=0,
    'CREATE INDEX idx_price_rule_org_product ON price_rule (organization_id, product_id)', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='price_rule' AND INDEX_NAME='idx_price_rule_org_category')=0,
    'CREATE INDEX idx_price_rule_org_category ON price_rule (organization_id, category_id)', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
