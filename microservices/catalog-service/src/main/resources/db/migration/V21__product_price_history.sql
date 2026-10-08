-- PR-1 — every change to a product's selling price, with where it came from. Design:
-- microservices/docs/selling-price-per-purchase-analysis.md (§4.4).
--
-- Written in the SAME transaction as the price change (ProductService), so a price never moves without its row and a
-- rolled-back purchase leaves neither. source: MANUAL (the product form, creation, import) | PURCHASE (goods-in
-- re-price, ref = the purchase's invoice number). Append-only: nothing updates or deletes these rows.
CREATE TABLE IF NOT EXISTS product_price_history (
    id               BIGINT         NOT NULL AUTO_INCREMENT,
    organization_id  BIGINT         NULL,
    product_id       BIGINT         NOT NULL,
    old_price        DECIMAL(19,2)  NULL,
    new_price        DECIMAL(19,2)  NULL,
    source           VARCHAR(20)    NOT NULL,
    ref              VARCHAR(80)    NULL,
    changed_by       BIGINT         NULL,
    changed_at       DATETIME       NOT NULL,
    PRIMARY KEY (id),
    KEY idx_price_history_product (organization_id, product_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
