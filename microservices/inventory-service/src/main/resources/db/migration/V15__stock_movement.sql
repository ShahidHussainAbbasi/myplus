-- SSR-0a (doc slices/110-stock-ledger-ssr.md) — the stock LEDGER: one append-only row per change of sellable on-hand.
--
-- Written by StockLedger in the SAME transaction as the change (the 5 places that write stock_levels.current_stock),
-- so a movement without its row cannot commit. Never updated, never deleted. SUM(quantity) per product = on-hand
-- from go-live on (SSR-0b writes the OPENING rows and reconciles).
--   quantity      signed, in the same unit as stock_levels.current_stock (packs), DECIMAL(19,4) like it
--   unit_cost     the batch's cost per unit when known (purchase price / pick unit cost)
--   movement_type StockMovementType (VARCHAR, not a MySQL ENUM — a new type must not need a migration)
--   business_date the shop's own day (TenantClock, TZ-2), what a period report filters on
-- Idempotent (D7): CREATE TABLE IF NOT EXISTS.
CREATE TABLE IF NOT EXISTS stock_movement (
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    organization_id  BIGINT        NULL,
    user_id          BIGINT        NULL,
    product_id       BIGINT        NOT NULL,
    stock_entry_id   BIGINT        NULL,
    movement_type    VARCHAR(24)   NOT NULL,
    quantity         DECIMAL(19,4) NOT NULL,
    unit_cost        DECIMAL(19,4) NULL,
    source_ref       VARCHAR(64)   NULL,
    business_date    DATE          NOT NULL,
    created_at       DATETIME      NOT NULL,
    PRIMARY KEY (id),
    KEY idx_sm_org_product_date (organization_id, product_id, business_date),
    KEY idx_sm_org_date (organization_id, business_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
