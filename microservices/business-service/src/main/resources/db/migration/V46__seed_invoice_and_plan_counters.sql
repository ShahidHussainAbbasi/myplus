-- Bring the last two per-org document numbers onto the serialised allocator (V45).
--
-- WHY THE REMAINING TWO ARE MOVING TOO
-- V45 converted credit notes, debit notes and quotes because their operations touch inventory before
-- allocating, so a collision there cannot be retried without restocking twice. Invoice and plan were left on
-- `SequenceRetry`, which recovers rather than prevents — it works, but it leaves TWO mechanisms answering one
-- question, and the retry only ever ran because MAX+1 had already failed.
--
-- One named pattern per concern. Prevention beats recovery: a retried sale does its work twice and a
-- serialised allocation does it once.
--
-- SEEDING IS THE ONLY WAY THIS MIGRATION CAN CORRUPT ANYTHING
-- A counter starting below a tenant's existing maximum hands out a number some invoice already carries, and
-- the UNIQUE constraint turns that into a failed sale on the shop's next transaction. MAX() per organisation,
-- COALESCE for the tenant that has issued none, and INSERT IGNORE so a re-run (FlywayConfig repairs then
-- migrates on every start) cannot reset a counter that is already in use.

INSERT IGNORE INTO org_document_seq (organization_id, doc_type, next_val, updated)
SELECT organization_id, 'INVOICE', COALESCE(MAX(invoice_seq), 0), NOW()
FROM customer_history
WHERE organization_id IS NOT NULL
GROUP BY organization_id;

INSERT IGNORE INTO org_document_seq (organization_id, doc_type, next_val, updated)
SELECT organization_id, 'PLAN', COALESCE(MAX(plan_seq), 0), NOW()
FROM installment_plan
WHERE organization_id IS NOT NULL
GROUP BY organization_id;
