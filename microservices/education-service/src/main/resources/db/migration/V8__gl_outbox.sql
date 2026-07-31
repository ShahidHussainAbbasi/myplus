-- Slice 0.1 — the education GL outbox.
--
-- A fee collection now enqueues a PENDING row here in the SAME transaction as the fee itself, so a committed fee
-- can never lose its journal entry. education-service delivers it to finance-service after commit, and a
-- scheduled relay re-drives anything undelivered.
--
-- This table belongs to myplusdb_education and is NOT shared with business-service's identically-named one. That
-- is the point of the outbox pattern: cross-service atomicity without a shared database.
--
-- Column set mirrors commerce-contracts PostingEventRequest, minus the fields a fee never uses (sub_total,
-- tax_total, cost, store_credit) — tuition carries no tax line and a service has no cost of goods.
--
-- Indexes (D3): (status, id) is the relay's drain query; (organization_id) supports per-tenant inspection.
-- Idempotent so a re-run or a dev DB that ddl-auto already touched is a no-op.

CREATE TABLE IF NOT EXISTS gl_outbox (
  id              BIGINT       NOT NULL AUTO_INCREMENT,
  event_type      VARCHAR(20)  NOT NULL,
  event_key       VARCHAR(64)  DEFAULT NULL,
  ref             VARCHAR(255) DEFAULT NULL,
  grand_total     DECIMAL(19,2) DEFAULT NULL,
  paid_amount     DECIMAL(19,2) DEFAULT NULL,
  method          VARCHAR(30)  DEFAULT NULL,
  status          VARCHAR(20)  NOT NULL,
  attempts        INT          NOT NULL,
  last_error      VARCHAR(500) DEFAULT NULL,
  organization_id BIGINT       DEFAULT NULL,
  user_id         BIGINT       DEFAULT NULL,
  created_at      DATETIME(6)  DEFAULT NULL,
  updated_at      DATETIME(6)  DEFAULT NULL,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='gl_outbox' AND INDEX_NAME='idx_edu_outbox_pending')=0,
    'CREATE INDEX idx_edu_outbox_pending ON gl_outbox (status, id)', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='gl_outbox' AND INDEX_NAME='idx_edu_outbox_org')=0,
    'CREATE INDEX idx_edu_outbox_org ON gl_outbox (organization_id)', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
