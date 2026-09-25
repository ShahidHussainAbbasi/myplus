-- PAID-1 preflight — READ-ONLY. Run against the production business DB (myplusdb) BEFORE deploying the
-- business-service build that carries V67__paid1_change_is_not_paid.sql. Nothing here writes.
-- Design: microservices/docs/slices/sale-dup-2-and-trade-discount-review.md, Part J.

-- A. What V67 will repair, per tenant.
SELECT organization_id,
       COUNT(*)            AS invoices_to_fix,
       SUM(change_amount)  AS change_recorded_as_paid,
       MIN(dated)          AS first_seen,
       MAX(dated)          AS last_seen
FROM customer_history
WHERE change_amount > 0.005 AND paid_amount > grand_total + 0.005 AND (status IS NULL OR status <> 'VOID')
GROUP BY organization_id
ORDER BY invoices_to_fix DESC;

-- B. ⚠ Customers whose balance will RISE — the change was hiding debt they really owe. SHOW THE OWNER THIS LIST.
SELECT c.organization_id, c.customer_id, c.name,
       c.due_amount                                   AS balance_now,
       GREATEST(0, -SUM(CASE WHEN ch.change_amount > 0.005 AND ch.paid_amount > ch.grand_total + 0.005
                                  AND (ch.status IS NULL OR ch.status <> 'VOID')
                             THEN (ch.paid_amount - ch.change_amount) - ch.grand_total
                             ELSE ch.due_amount END)) AS balance_after_v67
FROM customer c
JOIN customer_history ch ON ch.customer_id = c.customer_id
GROUP BY c.organization_id, c.customer_id, c.name, c.due_amount
HAVING balance_after_v67 <> balance_now
ORDER BY c.organization_id, (balance_after_v67 - balance_now) DESC;

-- C. ⚠ VOIDED sales that had change — the void refunded paid_amount, i.e. the change a SECOND time. SQL cannot
--    return that cash; the owner decides.
SELECT organization_id, invoice_no, dated, voided_at, grand_total, tendered_amount, change_amount,
       paid_amount AS refunded_at_void
FROM customer_history
WHERE change_amount > 0.005 AND status = 'VOID'
ORDER BY organization_id, voided_at DESC;

-- D. ⚠ Sales with change that were later RETURNED with a cash refund — the refund was computed from the inflated
--    paid_amount, so it may include the change again. Check each against its credit note.
SELECT ch.organization_id, ch.invoice_no, ch.grand_total, ch.tendered_amount, ch.change_amount,
       SUM(p.amount) AS refunded
FROM customer_history ch
JOIN payment p ON p.customer_history_id = ch.customer_history_id AND p.method = 'REFUND'
WHERE ch.change_amount > 0.005
GROUP BY ch.organization_id, ch.invoice_no, ch.grand_total, ch.tendered_amount, ch.change_amount
ORDER BY ch.organization_id, ch.invoice_no;
