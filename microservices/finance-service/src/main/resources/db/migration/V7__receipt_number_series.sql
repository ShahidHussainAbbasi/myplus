-- DOC-INT B — receipt and payment-voucher numbers come from a per-org counter, not COUNT(*) + 1.
-- Design: microservices/docs/slices/doc-int-numbers-and-bills.md
--
-- WHAT WAS WRONG
-- PaymentService numbered a receipt `COUNT(payments of this direction in this org) + 1`. Two receipts recorded
-- at the same moment both count the same total and both take the same number. Nothing refused the second:
-- receipt_no had no UNIQUE index. Not theoretical — the local ledger held 2 duplicate (org, receipt_no) pairs
-- when this was written (2026-09-14).
--
-- THE MECHANISM — the one business-service already runs (its V45)
-- A counter row per (organization_id, doc_type), bumped with UPDATE … next_val + 1. The row lock that UPDATE
-- takes is what makes a second receipt WAIT instead of reading a stale count. The bump joins record()'s
-- transaction (DocumentNumberService, Propagation.MANDATORY), so a receipt that fails gives its number back.
-- doc_type is the payment direction: RECEIPT → RCPT-######, DISBURSEMENT → PV-######.
--
-- WHY A NEW receipt_seq COLUMN, AND NOT A UNIQUE ON receipt_no
-- Duplicates already exist, so UNIQUE(receipt_no) cannot be created without renumbering receipts that are
-- printed and in customers' hands. receipt_seq starts NULL on every existing row (MySQL NULLs are distinct), so
-- the UNIQUE below binds from the first new receipt on and never has to judge history.
--
-- ⚠ SEEDING IS THE ONE WAY THIS CAN DO HARM. A counter below a number already issued would re-issue that number
-- as a string — and legacy rows carry no receipt_seq, so the UNIQUE could not catch it. Hence:
--   * RECEIPT seeds from the highest RCPT-###### over EVERY direction — two legacy DISBURSEMENT rows carry an
--     RCPT- number from before the PV- prefix existed, and a new RCPT- number must clear them too;
--   * DISBURSEMENT seeds from the highest PV-######;
--   * the UPDATE raises and never lowers (GREATEST) — the V63 rule: a gap is a question an auditor asks, a
--     duplicate receipt number is not a question anybody can answer.
--
-- Guarded where DDL could meet an already-migrated schema, so a re-run (FlywayConfig repairs, then migrates) is
-- a no-op. MySQL-specific (PREPARE / information_schema), like every guarded migration on this platform.

CREATE TABLE IF NOT EXISTS org_document_seq (
    organization_id     BIGINT       NOT NULL,
    -- RECEIPT | DISBURSEMENT. VARCHAR, not ENUM: a new value in a MySQL ENUM needs ALTER … MODIFY, and without it
    -- the insert fails with "Data truncated".
    doc_type            VARCHAR(16)  NOT NULL,
    -- The LAST number issued, not the next one: a fresh counter is 0 and the first receipt is 1.
    next_val            BIGINT       NOT NULL DEFAULT 0,
    updated             DATETIME     DEFAULT NULL,
    PRIMARY KEY (organization_id, doc_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- BIGINT because Payment.receiptSeq is a Long and ddl-auto=validate compares types.
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payments' AND COLUMN_NAME = 'receipt_seq') = 0,
    'ALTER TABLE payments ADD COLUMN receipt_seq BIGINT NULL',
    'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- Per org AND per direction: RCPT-000042 and PV-000042 are different documents.
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payments' AND INDEX_NAME = 'uq_pay_org_dir_seq') = 0,
    'CREATE UNIQUE INDEX uq_pay_org_dir_seq ON payments (organization_id, direction, receipt_seq)',
    'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- ── seed: a counter for every series that has numbers, at the highest number issued ─────────────────────
INSERT IGNORE INTO org_document_seq (organization_id, doc_type, next_val, updated)
SELECT s.organization_id, s.doc_type, s.max_seq, NOW()
  FROM (
        SELECT organization_id, 'RECEIPT' AS doc_type, MAX(CAST(SUBSTRING(receipt_no, 6) AS UNSIGNED)) AS max_seq
          FROM payments
         WHERE organization_id IS NOT NULL AND receipt_no REGEXP '^RCPT-[0-9]+$'
         GROUP BY organization_id
        UNION ALL
        SELECT organization_id, 'DISBURSEMENT', MAX(CAST(SUBSTRING(receipt_no, 4) AS UNSIGNED))
          FROM payments
         WHERE organization_id IS NOT NULL AND receipt_no REGEXP '^PV-[0-9]+$'
         GROUP BY organization_id
       ) s;

-- ── and raise any counter that already existed below it (never lower one) ───────────────────────────────
UPDATE org_document_seq c
  JOIN (
        SELECT organization_id, 'RECEIPT' AS doc_type, MAX(CAST(SUBSTRING(receipt_no, 6) AS UNSIGNED)) AS max_seq
          FROM payments
         WHERE organization_id IS NOT NULL AND receipt_no REGEXP '^RCPT-[0-9]+$'
         GROUP BY organization_id
        UNION ALL
        SELECT organization_id, 'DISBURSEMENT', MAX(CAST(SUBSTRING(receipt_no, 4) AS UNSIGNED))
          FROM payments
         WHERE organization_id IS NOT NULL AND receipt_no REGEXP '^PV-[0-9]+$'
         GROUP BY organization_id
       ) s
    ON s.organization_id = c.organization_id
   AND s.doc_type        = c.doc_type
   SET c.next_val = GREATEST(c.next_val, s.max_seq),
       c.updated  = NOW()
 WHERE c.next_val < s.max_seq;
