-- Reconcile every per-org document counter with the numbers actually issued.
--
-- WHAT THIS CLOSES
-- org_document_seq.next_val holds the LAST number handed out for (organization_id, doc_type); the allocator
-- bumps it and reads it back (DocumentNumberService.next). If that counter ever falls BEHIND the highest
-- number really present in the documents, the next allocation re-issues a number that already exists and the
-- insert dies on the series' UNIQUE index — observed in the wild as:
--
--     Duplicate entry '13-958' for key 'customer_history.uq_ch_org_invoice_seq'
--
-- ⚠ AND IT CANNOT HEAL ITSELF. next() is Propagation.MANDATORY, so the bump lives in the CALLER's
-- transaction: when the insert fails, the bump rolls back with it. The counter is left exactly where it was,
-- the next sale allocates the same colliding number, and the tenant can no longer issue that document at all
-- — for ever, with no message anyone at the counter can act on. One drift is permanent until somebody runs
-- SQL by hand, which is precisely the manual production step this migration exists to remove.
--
-- WHY A MIGRATION AND NOT A ONE-OFF FIX FOR THE ORG THAT DRIFTED
-- A hand-run UPDATE repairs one row in one environment and leaves every other environment — staging, a
-- restored backup, a customer's database, the next one to drift — to be found by a cashier. Reconciliation
-- belongs where every deploy runs it.
--
-- ⚠ RAISES, NEVER LOWERS. GREATEST() is load-bearing: a counter AHEAD of the documents is legitimate
-- (allocated numbers whose transaction rolled back leave a gap, which is normal and auditable), while
-- lowering one would re-issue numbers that are already on paper in a customer's hands. A gap is a question
-- an auditor asks; a duplicate invoice number is a different kind of conversation entirely.
--
-- Idempotent and re-runnable per D7: the second run matches no rows. Pure DML, no DDL to guard.
--
-- ── the six series, and where each number really lives ──────────────────────────────────────────
--   INVOICE      customer_history.invoice_seq
--   CREDIT_NOTE  sale_return.credit_note_seq          (sale returns AND repossession forfeits)
--   DEBIT_NOTE   purchase_return.debit_note_seq
--   QUOTE        sales_quote.quote_seq
--   PLAN         installment_plan.plan_seq
--   OPENING      customer_history.invoice_no — 'OB-000042'. ⚠ THE ODD ONE OUT: OpeningBalanceService
--                formats the allocated number straight into invoice_no and stores NO numeric column, so it
--                has to be parsed back out. Those rows carry invoice_seq NULL, which is why they do not
--                disturb the INVOICE series above.
--
-- Rows with a NULL organization_id are excluded throughout: they predate org scoping, org_document_seq
-- keys on a NOT NULL org, and a per-tenant counter is not a question those rows can answer.

-- ── 1. a counter row for every series that has documents but no counter ─────────────────────────
-- Without this, an org whose documents arrived before its counter did (a restore, an import) starts from
-- zero and collides on its very first allocation. INSERT IGNORE: the row usually exists already.
INSERT IGNORE INTO org_document_seq (organization_id, doc_type, next_val, updated)
SELECT s.organization_id, s.doc_type, 0, NOW()
  FROM (
        SELECT organization_id, 'INVOICE' AS doc_type, MAX(invoice_seq) AS max_seq
          FROM customer_history
         WHERE organization_id IS NOT NULL AND invoice_seq IS NOT NULL
         GROUP BY organization_id
        UNION ALL
        SELECT organization_id, 'CREDIT_NOTE', MAX(credit_note_seq)
          FROM sale_return
         WHERE organization_id IS NOT NULL AND credit_note_seq IS NOT NULL
         GROUP BY organization_id
        UNION ALL
        SELECT organization_id, 'DEBIT_NOTE', MAX(debit_note_seq)
          FROM purchase_return
         WHERE organization_id IS NOT NULL AND debit_note_seq IS NOT NULL
         GROUP BY organization_id
        UNION ALL
        SELECT organization_id, 'QUOTE', MAX(quote_seq)
          FROM sales_quote
         WHERE organization_id IS NOT NULL AND quote_seq IS NOT NULL
         GROUP BY organization_id
        UNION ALL
        SELECT organization_id, 'PLAN', MAX(plan_seq)
          FROM installment_plan
         WHERE organization_id IS NOT NULL AND plan_seq IS NOT NULL
         GROUP BY organization_id
        UNION ALL
        SELECT organization_id, 'OPENING',
               MAX(CAST(SUBSTRING(invoice_no, 4) AS UNSIGNED))
          FROM customer_history
         WHERE organization_id IS NOT NULL AND invoice_no REGEXP '^OB-[0-9]+$'
         GROUP BY organization_id
       ) s;

-- ── 2. raise any counter that has fallen behind what was actually issued ────────────────────────
-- The WHERE is what makes a re-run a no-op rather than a needless write of `updated`.
UPDATE org_document_seq c
  JOIN (
        SELECT organization_id, 'INVOICE' AS doc_type, MAX(invoice_seq) AS max_seq
          FROM customer_history
         WHERE organization_id IS NOT NULL AND invoice_seq IS NOT NULL
         GROUP BY organization_id
        UNION ALL
        SELECT organization_id, 'CREDIT_NOTE', MAX(credit_note_seq)
          FROM sale_return
         WHERE organization_id IS NOT NULL AND credit_note_seq IS NOT NULL
         GROUP BY organization_id
        UNION ALL
        SELECT organization_id, 'DEBIT_NOTE', MAX(debit_note_seq)
          FROM purchase_return
         WHERE organization_id IS NOT NULL AND debit_note_seq IS NOT NULL
         GROUP BY organization_id
        UNION ALL
        SELECT organization_id, 'QUOTE', MAX(quote_seq)
          FROM sales_quote
         WHERE organization_id IS NOT NULL AND quote_seq IS NOT NULL
         GROUP BY organization_id
        UNION ALL
        SELECT organization_id, 'PLAN', MAX(plan_seq)
          FROM installment_plan
         WHERE organization_id IS NOT NULL AND plan_seq IS NOT NULL
         GROUP BY organization_id
        UNION ALL
        SELECT organization_id, 'OPENING',
               MAX(CAST(SUBSTRING(invoice_no, 4) AS UNSIGNED))
          FROM customer_history
         WHERE organization_id IS NOT NULL AND invoice_no REGEXP '^OB-[0-9]+$'
         GROUP BY organization_id
       ) s
    ON s.organization_id = c.organization_id
   AND s.doc_type        = c.doc_type
   SET c.next_val = GREATEST(c.next_val, s.max_seq),
       c.updated  = NOW()
 WHERE c.next_val < s.max_seq;
