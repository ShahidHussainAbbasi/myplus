-- MKT-1e — the marketplace order: one-seller COD checkout, the seller's acceptance window, and the order-time
-- snapshot of every party, price and policy (source §8, §10, §13.3, §17.1, §19; design §5.3–5.4).
-- slice: docs/slices/mkt-1e-checkout-acceptance.md
--
-- VARCHAR statuses (never MySQL ENUM), DECIMAL(19,2) money, no JSON/TEXT columns: the snapshot is COLUMNS so
-- ddl-auto=validate checks it and a report can read it. Idempotent (D7).

-- ── 1. Document counters (common-docnum's port). Same DDL as expense-service's. ──────────────────────────
-- MKT- order numbers are one platform series: organization_id 0 (the platform), doc_type 'MKT'.
CREATE TABLE IF NOT EXISTS org_document_seq (
    organization_id  BIGINT        NOT NULL,
    doc_type         VARCHAR(16)   NOT NULL,
    next_val         BIGINT        NOT NULL DEFAULT 0,
    updated          DATETIME      DEFAULT NULL,
    PRIMARY KEY (organization_id, doc_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ── 2. The marketplace (parent) order. Belongs to the platform, so no organization_id: each seller's part is
--       a seller order below, keyed by the seller's org. ──────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS mkt_order (
  id                BIGINT        NOT NULL AUTO_INCREMENT,
  order_no          VARCHAR(32)   NOT NULL,
  idempotency_key   VARCHAR(80)   NOT NULL,
  status            VARCHAR(24)   NOT NULL,
  payment_mode      VARCHAR(16)   NOT NULL,
  payment_status    VARCHAR(24)   NOT NULL,
  customer_name     VARCHAR(120)  NOT NULL,
  customer_phone    VARCHAR(32)   NOT NULL,
  delivery_address  VARCHAR(300)  NOT NULL,
  city              VARCHAR(60)   NOT NULL,
  subtotal          DECIMAL(19,2) NOT NULL,
  delivery_fee      DECIMAL(19,2) NOT NULL,
  total             DECIMAL(19,2) NOT NULL,
  cancel_reason     VARCHAR(300)  DEFAULT NULL,
  created_at        DATETIME(6)   NOT NULL,
  updated_at        DATETIME(6)   DEFAULT NULL,
  version           INT           NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_mkt_order_no (order_no),
  UNIQUE KEY uk_mkt_order_idem (idempotency_key),
  KEY idx_mkt_order_status_created (status, created_at),            -- operator list by status, newest first
  KEY idx_mkt_order_phone_status (customer_phone, status)           -- the open-orders-per-phone guard
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ── 3. One seller's part of an order, and its acceptance window (source §10). ───────────────────────────
CREATE TABLE IF NOT EXISTS mkt_seller_order (
  id                      BIGINT        NOT NULL AUTO_INCREMENT,
  mkt_order_id            BIGINT        NOT NULL,
  seller_organization_id  BIGINT        NOT NULL,
  acceptance_status       VARCHAR(16)   NOT NULL,
  accept_by               DATETIME(6)   DEFAULT NULL,
  hold_key                VARCHAR(64)   NOT NULL,
  held                    BIT(1)        NOT NULL,
  store_order_id          BIGINT        DEFAULT NULL,
  store_order_no          VARCHAR(40)   DEFAULT NULL,
  invoice_no              VARCHAR(40)   DEFAULT NULL,
  reject_reason           VARCHAR(300)  DEFAULT NULL,
  decided_by_user_id      BIGINT        DEFAULT NULL,
  decided_at              DATETIME(6)   DEFAULT NULL,
  created_at              DATETIME(6)   NOT NULL,
  updated_at              DATETIME(6)   DEFAULT NULL,
  version                 INT           NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_mkt_seller_order_hold (hold_key),
  KEY idx_mkt_so_order (mkt_order_id),
  KEY idx_mkt_so_seller_queue (seller_organization_id, acceptance_status, accept_by),   -- the seller's queue
  KEY idx_mkt_so_sweep (acceptance_status, accept_by),                                    -- the expiry sweeper
  KEY idx_mkt_so_held (held, acceptance_status)                                          -- release retries
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ── 4. The order line: the snapshot (source §13.3 "order-time policy copies"). Never updated after insert
--       except settlement_status (MKT-1g). ────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS mkt_order_line (
  id                           BIGINT        NOT NULL AUTO_INCREMENT,
  seller_order_id              BIGINT        NOT NULL,
  offer_id                     BIGINT        NOT NULL,
  mkt_product_id               BIGINT        NOT NULL,
  source_product_id            BIGINT        NOT NULL,
  product_name                 VARCHAR(255)  NOT NULL,
  quantity                     INT           NOT NULL,
  unit_price                   DECIMAL(19,2) NOT NULL,
  line_total                   DECIMAL(19,2) NOT NULL,
  stock_source_type            VARCHAR(16)   NOT NULL,
  seller_organization_id       BIGINT        NOT NULL,
  stock_owner_organization_id  BIGINT        NOT NULL,
  custodian_organization_id    BIGINT        NOT NULL,
  fulfiller_organization_id    BIGINT        NOT NULL,
  promise_hours                INT           DEFAULT NULL,
  warranty_policy_id           BIGINT        DEFAULT NULL,
  warranty_provider            VARCHAR(120)  DEFAULT NULL,
  warranty_months              INT           DEFAULT NULL,
  warranty_starts              VARCHAR(16)   DEFAULT NULL,
  warranty_covers              VARCHAR(300)  DEFAULT NULL,
  warranty_excludes            VARCHAR(300)  DEFAULT NULL,
  return_policy_id             BIGINT        DEFAULT NULL,
  return_days                  INT           DEFAULT NULL,
  commission_policy_id         BIGINT        DEFAULT NULL,
  commission_basis             VARCHAR(24)   DEFAULT NULL,
  commission_rate              DECIMAL(9,6)  DEFAULT NULL,
  commission_fixed             DECIMAL(19,2) DEFAULT NULL,
  settlement_status            VARCHAR(24)   NOT NULL,
  created_at                   DATETIME(6)   NOT NULL,
  PRIMARY KEY (id),
  KEY idx_mkt_line_seller_order (seller_order_id),
  KEY idx_mkt_line_offer (offer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
