-- MKT-5d — counts of consigned stock and shrinkage claims (slice doc docs/slices/mkt-5d-counts-shrinkage.md).
--
-- 1. mkt_consignment_count: the warehouse counts one owner's product. Units found missing stay the owner's, set aside
--    in its books, but can no longer be sold, until the owner writes them off or a claim for them is approved.
-- 2. mkt_shrinkage_claim: the owner claims missing units from the custodian (R12.1-12.3). OPEN until the operator
--    approves or rejects it, or the owner withdraws it. Only an approval pays (R12.4).
-- 3. mkt_consigned_stock gains doc_no: the count or claim a LOST row resolves, in the uniqueness of a movement.
--
-- VARCHAR statuses (never MySQL ENUM), DECIMAL(19,2) money, ddl-auto=validate. Idempotent (D7).

CREATE TABLE IF NOT EXISTS mkt_consignment_count (
  id                      BIGINT         NOT NULL AUTO_INCREMENT,
  agreement_id            BIGINT         NOT NULL,                 -- the agreement in force when counted
  consignor_org_id        BIGINT         NOT NULL,
  consignee_org_id        BIGINT         NOT NULL,
  mkt_product_id          BIGINT         NOT NULL,
  product_name            VARCHAR(255)   NOT NULL,
  expected                INT            NOT NULL,                 -- held and not already known missing
  counted                 INT            NOT NULL,
  missing                 INT            NOT NULL,                 -- expected - counted, never below 0
  tolerated               INT            NOT NULL,                 -- of the missing, within the agreement's tolerance
  written_off             INT            NOT NULL DEFAULT 0,       -- resolved by the owner's write-off
  approved                INT            NOT NULL DEFAULT 0,       -- resolved by approved claims
  write_offs              INT            NOT NULL DEFAULT 0,       -- how many write-offs: the next one's number
  pending                 INT            NOT NULL DEFAULT 0,       -- being written off now (out of reach of a claim)
  note                    VARCHAR(300)   DEFAULT NULL,
  idempotency_key         VARCHAR(64)    NOT NULL,
  counted_by_user_id      BIGINT         DEFAULT NULL,
  counted_at              DATETIME(6)    NOT NULL,
  version                 INT            NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uq_mkt_count_key (consignee_org_id, idempotency_key),
  KEY idx_mkt_count_pair (consignor_org_id, consignee_org_id, mkt_product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS mkt_shrinkage_claim (
  id                      BIGINT         NOT NULL AUTO_INCREMENT,
  count_id                BIGINT         NOT NULL,
  agreement_id            BIGINT         NOT NULL,
  consignor_org_id        BIGINT         NOT NULL,                 -- the owner, who claims
  consignee_org_id        BIGINT         NOT NULL,                 -- the custodian, claimed against
  mkt_product_id          BIGINT         NOT NULL,
  product_name            VARCHAR(255)   NOT NULL,
  kind                    VARCHAR(20)    NOT NULL,                 -- THEFT, DAMAGE, ... (R12.1)
  responsible             VARCHAR(16)    NOT NULL,                 -- CONSIGNEE (only the custodian is claimed from)
  units                   INT            NOT NULL,
  amount                  DECIMAL(19,2)  NOT NULL,                 -- the units at their declared value
  proof                   VARCHAR(500)   DEFAULT NULL,
  note                    VARCHAR(300)   DEFAULT NULL,
  status                  VARCHAR(16)    NOT NULL,                 -- OPEN, APPROVED, REJECTED, WITHDRAWN
  decision_reason         VARCHAR(300)   DEFAULT NULL,
  settlement_ref          VARCHAR(30)    DEFAULT NULL,             -- the owner's settlement row when approved
  raised_by_user_id       BIGINT         DEFAULT NULL,
  raised_at               DATETIME(6)    NOT NULL,
  decided_by_user_id      BIGINT         DEFAULT NULL,
  decided_at              DATETIME(6)    DEFAULT NULL,
  version                 INT            NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_mkt_claim_count (count_id),
  KEY idx_mkt_claim_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mkt_consigned_stock' AND COLUMN_NAME = 'doc_no');
SET @s := IF(@c = 0, 'ALTER TABLE mkt_consigned_stock ADD COLUMN doc_no VARCHAR(20) NOT NULL DEFAULT ''''', 'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @c := (SELECT COUNT(*) FROM information_schema.STATISTICS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mkt_consigned_stock' AND INDEX_NAME = 'uq_mkt_consigned_doc');
SET @s := IF(@c = 0, 'ALTER TABLE mkt_consigned_stock ADD UNIQUE KEY uq_mkt_consigned_doc (transfer_id, movement, order_line_id, attempt, doc_no)', 'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @c := (SELECT COUNT(*) FROM information_schema.STATISTICS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mkt_consigned_stock' AND INDEX_NAME = 'uq_mkt_consigned_move');
SET @s := IF(@c > 0, 'ALTER TABLE mkt_consigned_stock DROP INDEX uq_mkt_consigned_move', 'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
