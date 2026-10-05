-- PR-4 — a price a purchase would set, waiting for the owner. Design: selling-price-per-purchase-analysis.md §11.
--
-- One row per proposal; at most one PENDING per product (a newer proposal turns the older SUPERSEDED). Decided rows are
-- final: APPROVED / REJECTED / SUPERSEDED are never re-opened. Approving writes the price through the same path as a
-- purchase (version bump, cache eviction, a price-history row with source APPROVAL), so this table never IS the price.
CREATE TABLE IF NOT EXISTS price_change_requests (
    id               BIGINT         NOT NULL AUTO_INCREMENT,
    organization_id  BIGINT         NULL,
    user_id          BIGINT         NULL,
    product_id       BIGINT         NOT NULL,
    current_price    DECIMAL(19,2)  NULL,
    proposed_price   DECIMAL(19,2)  NOT NULL,
    cost             DECIMAL(19,2)  NULL,
    source           VARCHAR(20)    NOT NULL,
    reason           VARCHAR(20)    NOT NULL,
    detail           VARCHAR(160)   NULL,
    ref              VARCHAR(80)    NULL,
    status           VARCHAR(12)    NOT NULL,
    proposed_by      BIGINT         NULL,
    proposed_at      DATETIME       NOT NULL,
    decided_by       BIGINT         NULL,
    decided_at       DATETIME       NULL,
    decision_note    VARCHAR(255)   NULL,
    PRIMARY KEY (id),
    KEY idx_pcr_org_status (organization_id, status, id),
    KEY idx_pcr_product (organization_id, product_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
