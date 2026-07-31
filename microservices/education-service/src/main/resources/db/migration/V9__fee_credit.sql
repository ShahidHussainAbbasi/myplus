-- Slice 0.2b — fee credit: money the school holds on a parent's behalf, normally an overpayment carried forward
-- to the next charge.
--
-- Mirrors business's store_credit_txn: an APPEND-ONLY, SIGNED ledger (+ issues, − redeems) plus a cached balance
-- on the owning entity. The balance is always the sum of the ledger, so it explains itself; student.credit_balance
-- is only a projection so a fee list need not re-sum per row.
--
-- The table lives here, in myplusdb_education — services share the RULES (common-credit), never the tables.
--
-- Index (D3): (organization_id, student_id) is exactly the balance query's predicate.
-- Idempotent so a re-run, or a dev DB ddl-auto already touched, is a no-op.

CREATE TABLE IF NOT EXISTS fee_credit_txn (
  id              BIGINT        NOT NULL AUTO_INCREMENT,
  organization_id BIGINT        DEFAULT NULL,
  user_id         BIGINT        DEFAULT NULL,
  student_id      BIGINT        NOT NULL,
  amount          DECIMAL(19,2) NOT NULL,
  reason          VARCHAR(30)   DEFAULT NULL,
  ref             VARCHAR(64)   DEFAULT NULL,
  dated           DATETIME(6)   DEFAULT NULL,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='fee_credit_txn'
                  AND INDEX_NAME='idx_fee_credit_org_student')=0,
    'CREATE INDEX idx_fee_credit_org_student ON fee_credit_txn (organization_id, student_id)', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- Cached balance on the student. Nullable = "no credit history yet", which reads as zero.
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='student' AND COLUMN_NAME='credit_balance')=0,
    'ALTER TABLE student ADD COLUMN credit_balance DECIMAL(19,2) DEFAULT NULL', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
