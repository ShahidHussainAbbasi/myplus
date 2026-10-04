-- MKT-1f — support cases, marketplace returns with the cost bearer, the delivery date, and this service's audit outbox.
-- slice: docs/slices/mkt-1f-support-returns.md
--
-- VARCHAR statuses (never ENUM), DECIMAL(19,2) money, DATETIME(6). Idempotent (D7): CREATE IF NOT EXISTS, and the one
-- ADD COLUMN guarded by information_schema.

-- ── 1. When the seller's store order was delivered. Stamped ONCE by the two writers that can deliver a store order
--       (OrderService.updateStatus, DeliveryService); the return window runs from here for the line's snapshotted
--       return_days. NULL = not delivered yet. ─────────────────────────────────────────────────────────────────────
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = 'mkt_seller_order' AND COLUMN_NAME = 'delivered_at') = 0,
  'ALTER TABLE mkt_seller_order ADD COLUMN delivered_at DATETIME(6) DEFAULT NULL', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- The hook finds the seller order by its store order: no index existed on store_order_id (V28).
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = 'mkt_seller_order' AND INDEX_NAME = 'idx_mkt_so_store_order') = 0,
  'CREATE INDEX idx_mkt_so_store_order ON mkt_seller_order (store_order_id)', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- ── 2. A support case: the customer's one conversation with MaxTheService about one order. ─────────────────────────
CREATE TABLE IF NOT EXISTS mkt_support_case (
  id                BIGINT        NOT NULL AUTO_INCREMENT,
  case_no           VARCHAR(32)   NOT NULL,
  mkt_order_id      BIGINT        NOT NULL,
  seller_org_id     BIGINT        NOT NULL,             -- the seller of that order: the org a task goes to
  customer_id       BIGINT        DEFAULT NULL,         -- NULL: an order not in an account (opened by number + phone)
  topic             VARCHAR(16)   NOT NULL,             -- ORDER_PROBLEM | RETURN | WARRANTY | OTHER
  status            VARCHAR(20)   NOT NULL,             -- OPEN | WAITING_SELLER | WAITING_CUSTOMER | RESOLVED
  urgent            BIT(1)        NOT NULL,
  resolution        VARCHAR(500)  DEFAULT NULL,
  created_at        DATETIME(6)   NOT NULL,
  updated_at        DATETIME(6)   DEFAULT NULL,
  version           INT           NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_mkt_case_no (case_no),
  KEY idx_mkt_case_order (mkt_order_id),
  KEY idx_mkt_case_queue (status, urgent, created_at),
  KEY idx_mkt_case_seller (seller_org_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ── 3. The thread. visible_to_customer = 0 is an internal note (operator ↔ operator, or a seller's raw reply until the
--       operator relays it); the customer's view never reads those rows. ──────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS mkt_support_message (
  id                   BIGINT        NOT NULL AUTO_INCREMENT,
  case_id              BIGINT        NOT NULL,
  author_kind          VARCHAR(16)   NOT NULL,          -- CUSTOMER | OPERATOR | SELLER | SYSTEM
  author_ref           BIGINT        DEFAULT NULL,      -- customer id, or user id
  body                 VARCHAR(2000) NOT NULL,
  visible_to_customer  BIT(1)        NOT NULL,
  created_at           DATETIME(6)   NOT NULL,
  PRIMARY KEY (id),
  KEY idx_mkt_msg_case (case_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ── 4. A return of one order line. The bearer is resolved WHEN OPENED from the line's party snapshot (R13.3) and never
--       re-read. The refund is a fact keyed "return:" + id in mkt_payment (card) or recorded here (cash at pickup). ───
CREATE TABLE IF NOT EXISTS mkt_return (
  id                 BIGINT        NOT NULL AUTO_INCREMENT,
  return_no          VARCHAR(32)   NOT NULL,
  case_id            BIGINT        NOT NULL,
  mkt_order_id       BIGINT        NOT NULL,
  order_line_id      BIGINT        NOT NULL,
  seller_org_id      BIGINT        NOT NULL,
  quantity           INT           NOT NULL,
  reason             VARCHAR(32)   NOT NULL,            -- ReturnCostPolicy.Reason
  bearer_role        VARCHAR(16)   NOT NULL,            -- ReturnCostPolicy.Party
  bearer_org_id      BIGINT        DEFAULT NULL,        -- NULL when the customer bears it
  status             VARCHAR(16)   NOT NULL,            -- REQUESTED | APPROVED | REJECTED | RECEIVED | REFUNDED
  outcome            VARCHAR(16)   DEFAULT NULL,        -- RESTOCK | QUARANTINE | WRITE_OFF
  line_amount        DECIMAL(19,2) NOT NULL,
  deduction          DECIMAL(19,2) NOT NULL,
  refund_amount      DECIMAL(19,2) NOT NULL,
  refund_channel     VARCHAR(16)   DEFAULT NULL,        -- CARD | CASH_AT_PICKUP
  credit_note_no     VARCHAR(200)  DEFAULT NULL,        -- the seller's credit note(s); set once: a retry never raises another
  decision_note      VARCHAR(500)  DEFAULT NULL,
  decided_by_user_id BIGINT        DEFAULT NULL,
  received_by_user_id BIGINT       DEFAULT NULL,
  created_at         DATETIME(6)   NOT NULL,
  updated_at         DATETIME(6)   DEFAULT NULL,
  version            INT           NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_mkt_return_no (return_no),
  KEY idx_mkt_return_case (case_id),
  KEY idx_mkt_return_line (order_line_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ── 5. This service's audit outbox (common-audit, E4/E5). The shared column set of AbstractAuditOutbox; the table is
--       ours (schema ownership). organization_id is the SUBJECT tenant (the seller), never the operator. ────────────
CREATE TABLE IF NOT EXISTS audit_outbox (
  id               BIGINT        NOT NULL AUTO_INCREMENT,
  action           VARCHAR(32)   NOT NULL,
  entity_type      VARCHAR(32)   NULL,
  entity_ref       VARCHAR(64)   NULL,
  amount           DECIMAL(19,2) NULL,
  details          VARCHAR(500)  NULL,
  reason           VARCHAR(255)  NULL,
  before_value     VARCHAR(64)   NULL,
  after_value      VARCHAR(64)   NULL,
  actor_org_id     BIGINT        NULL,
  actor_type       VARCHAR(24)   NULL,
  actor_email      VARCHAR(160)  NULL,
  event_key        VARCHAR(64)   NULL,
  occurred_at      DATETIME      NULL,
  status           VARCHAR(20)   NOT NULL,
  attempts         INT           NOT NULL,
  last_error       VARCHAR(500)  NULL,
  organization_id  BIGINT        NULL,
  user_id          BIGINT        NULL,
  created_at       DATETIME      NULL,
  updated_at       DATETIME      NULL,
  PRIMARY KEY (id),
  KEY idx_audit_outbox_pending (status, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
