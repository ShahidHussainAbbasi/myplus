-- MKT-2b — a seller's part that is not fulfilled (rejected or not accepted in time): its cause and responsible
-- party, and what became of it — moved to another seller, offered to the customer for approval, or cancelled
-- (source §11, §12.4). slice: docs/slices/mkt-2b-shortage-reroute.md
--
-- VARCHAR statuses (never MySQL ENUM), DECIMAL(19,2) money, ddl-auto=validate. Idempotent (D7).

-- ── 1. A part learns whether its shortage is still being resolved (the order must not end meanwhile) and, for a
--       part made by a reroute, which part it replaces. ─────────────────────────────────────────────────────────
SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mkt_seller_order' AND COLUMN_NAME = 'shortage_pending');
SET @s := IF(@c = 0, 'ALTER TABLE mkt_seller_order ADD COLUMN shortage_pending BIT(1) NOT NULL DEFAULT b''0''', 'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mkt_seller_order' AND COLUMN_NAME = 'replaces_seller_order_id');
SET @s := IF(@c = 0, 'ALTER TABLE mkt_seller_order ADD COLUMN replaces_seller_order_id BIGINT DEFAULT NULL', 'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- ── 2. One row per unfulfilled part. The cause and party are a RECORD (R11.4): nothing is debited from it (R12.4);
--       the seller may dispute it and an operator decides. The proposal columns hold an alternative offered to the
--       customer, with its own stock hold, until they answer or it expires. ─────────────────────────────────────
CREATE TABLE IF NOT EXISTS mkt_shortage (
  id                           BIGINT         NOT NULL AUTO_INCREMENT,
  mkt_order_id                 BIGINT         NOT NULL,
  seller_order_id              BIGINT         NOT NULL,                 -- the part that was not fulfilled
  seller_organization_id       BIGINT         NOT NULL,
  cause                        VARCHAR(32)    NOT NULL,                 -- MarketplaceShortage.Cause
  responsible_role             VARCHAR(16)    NOT NULL,                 -- MERCHANT, SUPPLIER, PLATFORM, ...
  responsible_org_id           BIGINT         DEFAULT NULL,
  evidence                     VARCHAR(500)   DEFAULT NULL,             -- the seller's words, or what the clock saw
  result                       VARCHAR(32)    NOT NULL,                 -- PENDING, then MarketplaceStatus.ShortageResult
  status                       VARCHAR(16)    NOT NULL,                 -- RECORDED, DISPUTED, UPHELD, OVERTURNED
  dispute_note                 VARCHAR(500)   DEFAULT NULL,
  decision_note                VARCHAR(500)   DEFAULT NULL,
  decided_by_user_id           BIGINT         DEFAULT NULL,
  decided_at                   DATETIME(6)    DEFAULT NULL,
  replacement_seller_order_id  BIGINT         DEFAULT NULL,
  proposal_seller_org_id       BIGINT         DEFAULT NULL,
  proposal_hold_key            VARCHAR(64)    DEFAULT NULL,
  proposal_held                BIT(1)         NOT NULL DEFAULT b'0',
  proposal_lines               VARCHAR(2000)  DEFAULT NULL,             -- offerId:qty:price;... (at most 10 lines)
  proposal_total               DECIMAL(19,2)  DEFAULT NULL,
  proposal_promise_hours       INT            DEFAULT NULL,
  proposal_expires_at          DATETIME(6)    DEFAULT NULL,
  customer_decision            VARCHAR(16)    DEFAULT NULL,             -- ACCEPTED, DECLINED, EXPIRED, CANCELLED
  customer_decided_at          DATETIME(6)    DEFAULT NULL,
  attempts                     INT            NOT NULL DEFAULT 0,
  created_at                   DATETIME(6)    NOT NULL,
  resolved_at                  DATETIME(6)    DEFAULT NULL,
  updated_at                   DATETIME(6)    DEFAULT NULL,
  version                      INT            NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_mkt_shortage_part (seller_order_id),                    -- one record per unfulfilled part
  UNIQUE KEY uk_mkt_shortage_hold (proposal_hold_key),
  KEY idx_mkt_shortage_order (mkt_order_id),
  KEY idx_mkt_shortage_sweep (result, proposal_expires_at),               -- proposals that expire; pending retries
  KEY idx_mkt_shortage_seller (seller_organization_id, status),
  KEY idx_mkt_shortage_status (status, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
