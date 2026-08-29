-- U7 — the shop's own sticker.
--
-- Design: docs/slices/u7-own-stickers.md
--
-- A pharmacy prints "LP-4471", sticks it on the strip holder, and scanning it sells ONE TABLET. Today that
-- costs a typed marker (1L*CODE); this table lets the sticker carry the meaning.
--
--   barcode      "LP-4471"   the label the shop prints
--   product_id   88
--   sold_unit    LOOSE       what this code MEANS
--   quantity     1           and how many
--
-- ⚠ WHY THE UNIQUE INDEX IS NOT OPTIONAL.
-- This table is consulted on EVERY SCAN, before the ordinary barcode/sku query. Without a unique index on
-- (organization_id, barcode) that probe degrades into a scan of the tenant's codes on the busiest path in
-- the shop. The index is also the constraint: one code, one meaning, per tenant.
--
-- ⚠ WHAT THIS TABLE MUST NEVER DO: shadow a real product barcode. If a manufacturer GTIN were registered
-- here as "1 tablet", every scan of that pack would sell one tablet — the commonest transaction in the shop,
-- mis-priced, silently. The database cannot express that rule (it spans two tables), so it is enforced in
-- ProductBarcodeService in BOTH directions and gated. This comment exists so nobody later "simplifies" the
-- check away on the grounds that the unique index looks sufficient.
--
-- Version read from flyway_schema_history (head = V12), NOT `ls | tail` — which sorts lexically and is what
-- made U1's first migration report success = 1 while never opening the file.

CREATE TABLE IF NOT EXISTS product_barcode (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    organization_id   BIGINT       NOT NULL,
    barcode           VARCHAR(64)  NOT NULL,
    product_id        BIGINT       NOT NULL,
    -- PACK or LOOSE. Deliberately NOT an enum column: adding a value to a MySQL enum needs an
    -- ALTER ... MODIFY that ddl-auto will not generate, and this codebase has been bitten by that already.
    sold_unit         VARCHAR(8)   NOT NULL DEFAULT 'LOOSE',
    -- How many of sold_unit this code means. A whole number; the service refuses anything else.
    quantity          INT          NOT NULL DEFAULT 1,
    created_by        BIGINT       NULL,
    created_at        DATETIME     NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_product_barcode_org_code (organization_id, barcode),
    KEY idx_product_barcode_product (product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
