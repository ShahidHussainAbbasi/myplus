-- MKT-3c — a seller sends stock to the MaxTheService warehouse, and MaxTheService buys it (slice doc
-- docs/slices/mkt-3c-stock-transfer.md). One row per transfer: what the seller offers (product, quantity, price a unit),
-- and, once the warehouse receives it, the two documents it produced: the sale in the seller's books and the purchase,
-- owed to the seller, in the warehouse's. The marketplace keeps no stock of its own; these are links to the books.
--
-- VARCHAR statuses (never MySQL ENUM), DECIMAL(19,2) money, ddl-auto=validate. Idempotent (D7).
CREATE TABLE IF NOT EXISTS mkt_stock_transfer (
  id                      BIGINT         NOT NULL AUTO_INCREMENT,
  seller_org_id           BIGINT         NOT NULL,
  warehouse_org_id        BIGINT         NOT NULL,
  mkt_product_id          BIGINT         NOT NULL,
  seller_product_id       BIGINT         NOT NULL,                 -- the seller's own product (its books)
  warehouse_product_id    BIGINT         NOT NULL,                 -- the warehouse's own product (its books)
  product_name            VARCHAR(255)   NOT NULL,
  quantity                INT            NOT NULL,
  unit_price              DECIMAL(19,2)  NOT NULL,
  note                    VARCHAR(500)   DEFAULT NULL,
  status                  VARCHAR(16)    NOT NULL,                 -- OFFERED, RECEIVING, RECEIVED, REJECTED, CANCELLED
  reject_reason           VARCHAR(500)   DEFAULT NULL,
  serials                 VARCHAR(2000)  DEFAULT NULL,             -- comma-separated, as received
  sale_invoice_no         VARCHAR(40)    DEFAULT NULL,             -- the seller's invoice to MaxTheService
  purchase_id             BIGINT         DEFAULT NULL,             -- the warehouse's purchase from the seller
  attempt                 INT            NOT NULL DEFAULT 0,       -- part of both books' idempotency keys (see entity)
  offered_by_user_id      BIGINT         DEFAULT NULL,
  decided_by_user_id      BIGINT         DEFAULT NULL,
  created_at              DATETIME(6)    NOT NULL,
  decided_at              DATETIME(6)    DEFAULT NULL,
  version                 INT            NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_mkt_transfer_seller (seller_org_id, status),
  KEY idx_mkt_transfer_warehouse (warehouse_org_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
