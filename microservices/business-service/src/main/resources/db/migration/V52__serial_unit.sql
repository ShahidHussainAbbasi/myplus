-- SER-2 — the per-unit register. One row per physically identified item: an IMEI, an appliance serial.
--
-- THE GAP THIS CLOSES
-- Before this, an IMEI was captured ONLY when a handset was financed: InstallmentPlan.asset_ref, free text,
-- described by its own javadoc as "a LABEL, not a register". A shop that bought ten handsets and sold three
-- for cash recorded no IMEI at all — so it could not answer "who did we sell this one to?", which is the
-- question a warranty claim, a return and a police enquiry all begin with.
--
-- WHY THIS TABLE LIVES IN business-service
-- InstallmentPlan.serialUnitId's comment intended inventory-service. Three things argue against it, and the
-- ruling went the other way:
--   1. V44 already settled the principle for the sale path: "The obvious design is to ask inventory-service
--      whether the serial is already out. That check would fail OPEN the moment inventory-service is slow or
--      down — the sale would go through and the guarantee would be worth nothing precisely when the shop is
--      busiest." A serial's "not already sold" check is that same check.
--   2. Purchase and Sell are both business-service entities. Creation and consumption on one side of a
--      service boundary with the register on the other puts a remote call on the sale path.
--   3. inventory-service holds NO unit or batch identity at all — stock_entries is product_id + warehouse_id.
--      A per-unit register there would be the only identity-bearing row in a service that deals in quantities.
-- inventory-service owns QUANTITIES; business-service owns WHICH UNIT.
--
-- UNIQUENESS — the same technique V44 proved, and for the same reason
-- MySQL has no partial/filtered unique index, so the standard emulation is a STORED generated column that is
-- NULL for the rows the constraint must ignore. NULLs do not collide in a MySQL unique index, so:
--   * many SOLD/SCRAPPED rows carrying the same serial   -> live_serial_no NULL -> no collision
--   * two rows IN_STOCK with the same serial             -> COLLISION, refused. This is the safety property.
--
-- A PLAIN UNIQUE (organization_id, serial_no) WOULD BE A BUG. It would stop a shop ever taking back a handset
-- it legitimately sold and re-selling it — and a bought-back unit returning to the shelf is the whole point of
-- a register. The rule is "not IN STOCK twice", never "never twice".
--
-- Because the column is STORED and derived from `status`, it re-computes on UPDATE: selling a unit frees its
-- serial with nothing to remember. A release flag maintained by application code is what this avoids.
--
-- ⚠ Version 52: V51 is the highest existing. A duplicate version does not fail — Flyway sees the version in
-- its history, considers it applied and SILENTLY SKIPS the file. That is how catalog's V11 was lost when it
-- was first written as V10.

CREATE TABLE IF NOT EXISTS serial_unit (
    serial_unit_id   BIGINT       NOT NULL AUTO_INCREMENT,
    organization_id  BIGINT       NOT NULL,
    user_id          BIGINT       NULL,            -- audit: who registered it
    store_id         BIGINT       NULL,            -- multi-location: which branch holds it
    product_id       BIGINT       NOT NULL,
    serial_no        VARCHAR(64)  NOT NULL,

    -- SER-4: NEW / USED / REFURBISHED. Column added here rather than in a later ALTER because a second-hand
    -- trade grades a unit at the moment it is taken in, which is exactly when the row is created.
    condition_grade  VARCHAR(16)  NOT NULL DEFAULT 'NEW',

    -- IN_STOCK / SOLD / SCRAPPED. Drives live_serial_no below, so it is not merely descriptive.
    status           VARCHAR(16)  NOT NULL DEFAULT 'IN_STOCK',

    purchase_id      BIGINT       NULL,            -- how it arrived
    sell_id          BIGINT       NULL,            -- how it left; null while in stock
    dated            DATETIME     NULL,
    updated          DATETIME     NULL,

    -- The partial-unique emulation. NULL unless the unit is in stock, so only live units compete.
    live_serial_no   VARCHAR(64)  GENERATED ALWAYS AS (IF(status = 'IN_STOCK', serial_no, NULL)) STORED,

    PRIMARY KEY (serial_unit_id),
    UNIQUE KEY uq_serial_unit_live (organization_id, live_serial_no),
    KEY idx_serial_unit_org_product (organization_id, product_id),
    KEY idx_serial_unit_org_serial  (organization_id, serial_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
