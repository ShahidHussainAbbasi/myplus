-- MKT-1g — commission, the append-only settlement ledger, T+N eligibility, manual payouts, and the outbox that
-- carries the operator's journals to finance (source §15, §16, §22.3; design §5.5; ruling R-MKT-3).
-- slice: docs/slices/mkt-1g-settlement-payouts.md
--
-- VARCHAR statuses (never MySQL ENUM), DECIMAL(19,2) money, ddl-auto=validate. Idempotent (D7).

-- ── 1. The order line learns which payout settles it. settlement_status was already here (V28); payout_id is the
--       only other column a line ever gains after insert. ────────────────────────────────────────────────────
SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mkt_order_line' AND COLUMN_NAME = 'payout_id');
SET @s := IF(@c = 0, 'ALTER TABLE mkt_order_line ADD COLUMN payout_id BIGINT DEFAULT NULL', 'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- the settlement sweeper: lines still waiting, oldest first
SET @c := (SELECT COUNT(*) FROM information_schema.STATISTICS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mkt_order_line' AND INDEX_NAME = 'idx_mkt_line_settlement');
SET @s := IF(@c = 0, 'ALTER TABLE mkt_order_line ADD KEY idx_mkt_line_settlement (settlement_status, seller_organization_id, id)', 'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- ── 2. The settlement ledger. APPEND-ONLY: the code has no update path, and a correction is a new row. A seller's
--       balance is SUM(credit) − SUM(debit) over its rows; no balance column exists to drift. ────────────────────
CREATE TABLE IF NOT EXISTS mkt_settlement_entry (
  id                 BIGINT        NOT NULL AUTO_INCREMENT,
  organization_id    BIGINT        NOT NULL,                 -- the SELLER whose account this is
  order_line_id      BIGINT        DEFAULT NULL,
  payout_id          BIGINT        DEFAULT NULL,
  entry_type         VARCHAR(24)   NOT NULL,                 -- MarketplaceStatus.LedgerEntryType
  debit_amount       DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  credit_amount      DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  currency           VARCHAR(3)    NOT NULL,
  ref                VARCHAR(40)   NOT NULL,                 -- MKT-… order, PO-… payout, ADJ-… correction
  memo               VARCHAR(300)  DEFAULT NULL,
  effective_at       DATETIME(6)   NOT NULL,
  idempotency_key    VARCHAR(80)   NOT NULL,
  created_by_user_id BIGINT        DEFAULT NULL,
  created_at         DATETIME(6)   NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_mkt_entry_idem (idempotency_key),
  KEY idx_mkt_entry_account (organization_id, id),          -- the statement and the balance
  KEY idx_mkt_entry_line (order_line_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ── 3. A manual payout: requested by one operator, approved by ANOTHER, marked paid with the bank reference. ──
CREATE TABLE IF NOT EXISTS mkt_payout (
  id                   BIGINT        NOT NULL AUTO_INCREMENT,
  payout_no            VARCHAR(32)   NOT NULL,
  organization_id      BIGINT        NOT NULL,               -- the seller paid
  requested_amount     DECIMAL(19,2) NOT NULL,
  approved_amount      DECIMAL(19,2) DEFAULT NULL,
  status               VARCHAR(16)   NOT NULL,               -- REQUESTED | APPROVED | PAID
  bank_reference       VARCHAR(80)   DEFAULT NULL,
  idempotency_key      VARCHAR(80)   NOT NULL,
  requested_by_user_id BIGINT        DEFAULT NULL,
  approved_by_user_id  BIGINT        DEFAULT NULL,
  paid_by_user_id      BIGINT        DEFAULT NULL,
  requested_at         DATETIME(6)   NOT NULL,
  approved_at          DATETIME(6)   DEFAULT NULL,
  paid_at              DATETIME(6)   DEFAULT NULL,
  updated_at           DATETIME(6)   DEFAULT NULL,
  version              INT           NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_mkt_payout_no (payout_no),
  UNIQUE KEY uk_mkt_payout_idem (idempotency_key),
  KEY idx_mkt_payout_seller (organization_id, status),
  KEY idx_mkt_payout_status (status, requested_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ── 4. The operator's journals on their way to finance (the expense_outbox shape: the WHOLE request as JSON, so
--       a contract field is never dropped column by column). organization_id is the OPERATOR's books. ─────────
CREATE TABLE IF NOT EXISTS mkt_gl_outbox (
  id               BIGINT         NOT NULL AUTO_INCREMENT,
  organization_id  BIGINT         NULL,
  user_id          BIGINT         NULL,
  event_type       VARCHAR(24)    NOT NULL,                  -- MKT_SETTLEMENT | MKT_ADJUSTMENT | MKT_PAYOUT
  event_key        VARCHAR(80)    NOT NULL,                  -- finance dedups on it
  payload          VARCHAR(4000)  NOT NULL,
  status           VARCHAR(20)    NOT NULL DEFAULT 'PENDING',
  attempts         INT            NOT NULL DEFAULT 0,
  last_error       VARCHAR(500)   NULL,
  created_at       DATETIME       NULL,
  updated_at       DATETIME       NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_mkt_gl_outbox_event_key (event_key),
  KEY idx_mkt_gl_outbox_status (status, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
