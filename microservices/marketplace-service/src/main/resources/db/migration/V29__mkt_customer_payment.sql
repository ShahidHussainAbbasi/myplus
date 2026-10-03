-- MKT-1e2 — the marketplace customer (phone + password), their sessions, and the payment facts of an order.
-- slice: docs/slices/mkt-1e2-customer-account-payment.md
--
-- VARCHAR statuses (never ENUM), DECIMAL(19,2) money. Idempotent (D7). Platform-scoped: no organization_id —
-- the customer belongs to MaxTheService (R-MKT-5); the per-store storefront_customer is untouched.

-- ── 1. The customer. One account per phone (digits only). The phone is NOT proven (no SMS provider): an order
--       joins an account only by proof — placed signed in, or claimed with its number + phone. ──────────────
CREATE TABLE IF NOT EXISTS mkt_customer (
  id               BIGINT        NOT NULL AUTO_INCREMENT,
  phone            VARCHAR(32)   NOT NULL,
  name             VARCHAR(120)  NOT NULL,
  email            VARCHAR(254)  DEFAULT NULL,
  password_hash    VARCHAR(100)  NOT NULL,
  failed_logins    INT           NOT NULL DEFAULT 0,
  locked_until     DATETIME(6)   DEFAULT NULL,
  created_at       DATETIME(6)   NOT NULL,
  updated_at       DATETIME(6)   DEFAULT NULL,
  version          INT           NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_mkt_customer_phone (phone)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ── 2. Sessions. The token is never stored: only its SHA-256 (64 hex). Idle expiry, revocation. ───────────
CREATE TABLE IF NOT EXISTS mkt_customer_session (
  id               BIGINT        NOT NULL AUTO_INCREMENT,
  customer_id      BIGINT        NOT NULL,
  token_hash       CHAR(64)      NOT NULL,
  created_at       DATETIME(6)   NOT NULL,
  last_seen_at     DATETIME(6)   NOT NULL,
  expires_at       DATETIME(6)   NOT NULL,
  revoked_at       DATETIME(6)   DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_mkt_session_token (token_hash),
  KEY idx_mkt_session_customer (customer_id),
  CONSTRAINT fk_mkt_session_customer FOREIGN KEY (customer_id) REFERENCES mkt_customer (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ── 3. Payment facts of an order: every charge and refund, once (idempotency key). MKT-1g's ledger reads
--       these; nothing is paid out to a seller before it. ────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS mkt_payment (
  id               BIGINT        NOT NULL AUTO_INCREMENT,
  mkt_order_id     BIGINT        NOT NULL,
  kind             VARCHAR(16)   NOT NULL,
  status           VARCHAR(16)   NOT NULL,
  amount           DECIMAL(19,2) NOT NULL,
  provider         VARCHAR(32)   NOT NULL,
  provider_ref     VARCHAR(80)   DEFAULT NULL,
  idempotency_key  VARCHAR(100)  NOT NULL,
  reason           VARCHAR(300)  DEFAULT NULL,
  created_at       DATETIME(6)   NOT NULL,
  updated_at       DATETIME(6)   DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_mkt_payment_idem (idempotency_key),
  KEY idx_mkt_payment_order (mkt_order_id),
  KEY idx_mkt_payment_status (status, created_at),
  CONSTRAINT fk_mkt_payment_order FOREIGN KEY (mkt_order_id) REFERENCES mkt_order (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ── 4. The order's owner, when proven. Nullable: every anonymous order keeps NULL, the honest value. ────────
SET @sql := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='mkt_order' AND COLUMN_NAME='customer_id')=0,
    'ALTER TABLE mkt_order ADD COLUMN customer_id BIGINT NULL AFTER customer_phone', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- "My orders", newest first.
SET @sql := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='mkt_order' AND INDEX_NAME='idx_mkt_order_customer_created')=0,
    'CREATE INDEX idx_mkt_order_customer_created ON mkt_order (customer_id, created_at)', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
