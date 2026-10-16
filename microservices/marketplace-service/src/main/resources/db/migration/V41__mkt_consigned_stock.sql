-- MKT-5b — a seller consigns stock to the MaxTheService warehouse under its active consignment agreement (slice doc
-- docs/slices/mkt-5b-consigned-stock.md). Two changes:
--
-- 1. mkt_stock_transfer learns its KIND. BUY (every row before this, MKT-3c: MaxTheService buys the stock) or CONSIGN
--    (the seller keeps owning it; the warehouse holds it). A CONSIGN row names the agreement it was offered under and,
--    once received, the hold that sets the stock aside in the seller's own books.
-- 2. mkt_consigned_stock, the consigned stock ledger (R20.5): one row per movement of consigned stock, signed quantity.
--    What the warehouse holds for a consignor is the sum. 5b writes RECEIVED; selling, counting and returning add their
--    own movements in later slices.
--
-- VARCHAR statuses (never MySQL ENUM), DECIMAL(19,2) money, ddl-auto=validate. Idempotent (D7).

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mkt_stock_transfer' AND COLUMN_NAME = 'kind');
SET @s := IF(@c = 0, 'ALTER TABLE mkt_stock_transfer ADD COLUMN kind VARCHAR(16) NOT NULL DEFAULT ''BUY''', 'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mkt_stock_transfer' AND COLUMN_NAME = 'agreement_id');
SET @s := IF(@c = 0, 'ALTER TABLE mkt_stock_transfer ADD COLUMN agreement_id BIGINT NULL', 'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mkt_stock_transfer' AND COLUMN_NAME = 'hold_key');
SET @s := IF(@c = 0, 'ALTER TABLE mkt_stock_transfer ADD COLUMN hold_key VARCHAR(64) NULL', 'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

CREATE TABLE IF NOT EXISTS mkt_consigned_stock (
  id                      BIGINT         NOT NULL AUTO_INCREMENT,
  agreement_id            BIGINT         NOT NULL,                 -- the agreement in force when it moved
  consignor_org_id        BIGINT         NOT NULL,                 -- the owner
  consignee_org_id        BIGINT         NOT NULL,                 -- the holder: the MaxTheService warehouse
  mkt_product_id          BIGINT         NOT NULL,
  consignor_product_id    BIGINT         NOT NULL,                 -- the owner's own product (its books)
  product_name            VARCHAR(255)   NOT NULL,
  movement                VARCHAR(16)    NOT NULL,                 -- RECEIVED (5b); later SOLD, RETURNED, SHRINKAGE
  quantity                INT            NOT NULL,                 -- signed: + into the warehouse, - out of it
  unit_value              DECIMAL(19,2)  NOT NULL,                 -- the value a unit the consignor declared
  serials                 VARCHAR(2000)  DEFAULT NULL,             -- comma-separated, as scanned
  transfer_id             BIGINT         DEFAULT NULL,             -- the mkt_stock_transfer it came in on
  hold_key                VARCHAR(64)    DEFAULT NULL,             -- the hold in the consignor's books
  created_by_user_id      BIGINT         DEFAULT NULL,
  created_at              DATETIME(6)    NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_mkt_consigned_transfer (transfer_id, movement),
  KEY idx_mkt_consigned_pair (consignor_org_id, consignee_org_id, mkt_product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
