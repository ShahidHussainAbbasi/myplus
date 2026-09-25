-- PAID-1 — change handed back is not money the shop kept.
-- Design: microservices/docs/slices/sale-dup-2-and-trade-discount-review.md, Part J.
--
-- ── WHAT WAS WRONG ─────────────────────────────────────────────────────────────────────────────────────────
-- PaymentService.settle() returned paid = the WHOLE tender. INV-000054 (org 15): 272.00 handed over for a 42.00
-- bill was stored paid 272.00, due +230.00, payment CASH 272.00. Every reader then treated the 230.00 of change as
-- money received:
--   * voiding refunded paid_amount (272)          — the change handed back a second time
--   * a return refunded paid − new total          — the change handed back again
--   * CustomerService.recomputeDue sums due BEFORE flooring, so +230 hid 230 of the customer's other debt
--   * the shift's expected cash (Σ payment.amount) was 230 higher than the drawer
-- The code now keeps paid = min(tender, bill) and records change as a CASH −change 'CHANGE' payment row. THIS
-- migration repairs the rows written before that.
--
-- ── WHAT IT CHANGES ────────────────────────────────────────────────────────────────────────────────────────
-- Only invoices with change AND paid above the bill AND not VOID (dev: 72 rows, 4 tenants):
--   1. a BEFORE-IMAGE of each row into paid1_backup (irreversible data changes keep what they overwrote);
--   2. paid = old paid − change (what the drawer kept), due = paid − grand_total;
--   3. the missing CASH −change 'CHANGE' payment row, dated with the sale;
--   4. customer.due_amount recomputed for every customer touched — the same rule as recomputeDue
--      (−Σ invoice due, floored at 0), with version bumped so a stale open form cannot write the old figure back.
-- ⚠ A customer's balance can RISE here: that is the change that was hiding debt they genuinely owe. Run
--   docs/deploy/preflight-paid1.sql on production FIRST and show the owner that list.
-- ⚠ Voids and returns that ALREADY refunded inflated change cannot be repaired by SQL — the cash left the drawer.
--   The preflight lists them; this migration does not touch VOID invoices or REFUND rows.
--
-- ── WHY EVERY STEP IS GUARDED ──────────────────────────────────────────────────────────────────────────────
-- customer_history and customer are MyISAM: no transaction can roll a half-run back. So each step is idempotent
-- and keyed on the before-image: a re-run after a failure finishes the job and never applies anything twice.

-- 1. Before-image.
CREATE TABLE IF NOT EXISTS paid1_backup (
    customer_history_id BIGINT        NOT NULL PRIMARY KEY,
    organization_id     BIGINT        NULL,
    customer_id         BIGINT        NULL,
    old_paid_amount     DECIMAL(19,2) NULL,
    old_due_amount      DECIMAL(19,2) NULL,
    change_amount       DECIMAL(19,2) NULL,
    grand_total         DECIMAL(19,2) NULL,
    backed_up_at        DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE = InnoDB;

INSERT IGNORE INTO paid1_backup
    (customer_history_id, organization_id, customer_id, old_paid_amount, old_due_amount, change_amount, grand_total)
SELECT customer_history_id, organization_id, customer_id, paid_amount, due_amount, change_amount, grand_total
FROM customer_history
WHERE change_amount > 0.005
  AND paid_amount > grand_total + 0.005
  AND (status IS NULL OR status <> 'VOID');

-- 2. The invoice keeps what the drawer kept. Only rows STILL holding the old figure (idempotent).
UPDATE customer_history ch
JOIN paid1_backup b ON b.customer_history_id = ch.customer_history_id
SET ch.paid_amount = GREATEST(b.old_paid_amount - b.change_amount, 0),
    ch.due_amount  = GREATEST(b.old_paid_amount - b.change_amount, 0) - ch.grand_total
WHERE ch.paid_amount = b.old_paid_amount;

-- 3. The cash that went back, as the code now records it. Once per invoice.
INSERT INTO payment (amount, customer_history_id, dated, method, organization_id, reference, user_id, store_id)
SELECT -b.change_amount, b.customer_history_id, ch.dated, 'CASH', b.organization_id, 'CHANGE', ch.user_id, ch.store_id
FROM paid1_backup b
JOIN customer_history ch ON ch.customer_history_id = b.customer_history_id
WHERE NOT EXISTS (SELECT 1 FROM payment p
                  WHERE p.customer_history_id = b.customer_history_id AND p.reference = 'CHANGE');

-- 4. Every touched customer's running balance, by recomputeDue's own rule.
UPDATE customer c
JOIN (SELECT ch.customer_id, GREATEST(0, -SUM(ch.due_amount)) AS owed
      FROM customer_history ch
      WHERE ch.customer_id IN (SELECT DISTINCT customer_id FROM paid1_backup WHERE customer_id IS NOT NULL)
      GROUP BY ch.customer_id) x ON x.customer_id = c.customer_id
SET c.due_amount = x.owed,
    c.version    = COALESCE(c.version, 0) + 1
WHERE c.due_amount IS NULL OR c.due_amount <> x.owed;
