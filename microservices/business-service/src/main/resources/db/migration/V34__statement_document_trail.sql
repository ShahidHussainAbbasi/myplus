-- Statements show the DOCUMENT TRAIL: credit/debit notes appear, and the issued value stops moving.
-- Slice b2b-P3f (docs/slices/b2b-P3f-credit-notes-on-statements.md).
--
-- THE PROBLEM: an invoice issued at 500 with a 200 return reads as a single BILL of 300, because a return
-- re-settles the invoice header in place (SellController.saleReturn) and the statement reads that header.
-- The running balance is right; the trail is not -- the customer's copy says 500, ours says 300, and the
-- credit note that explains the gap appears nowhere. 3c made those notes real documents; this makes them
-- visible on the statement they belong to.
--
-- THE MODEL (design ss2a, Option B): grand_total / total_amount KEEP their current meaning -- the settled
-- value -- so every existing reader (dues, aging, dashboard, GL, the 3e sale report) is untouched. A new
-- issued_total records the document AS ISSUED, and ONLY the statement reads it. The alternative (make
-- grand_total immutable) was rejected: it changes what that column MEANS for every consumer at once, and
-- would have silently switched the 3e sale report to gross-of-returns with no test failing to say so.
--
-- THE CUTOVER IS THE DATA, NOT A DATE: a credit note is shown iff it has a stored value, and only returns
-- taken after this migration have one. Pre-V34 statements therefore render EXACTLY as they do today. The
-- worst case (invoice pre-V34, one return each side of it) leaves the trail incomplete, never the balance
-- wrong -- it degrades in the safe direction.
--
-- AR AND AP DIFFER, DELIBERATELY. The supplier side always persisted its debit-note value
-- (purchase_return.amount), so AP history is reconstructable exactly and IS back-filled. The customer side
-- never persisted the credit note's value, and a FULL return DELETES the sell row -- so those values are
-- gone permanently. Inventing them on a customer-facing document would be worse than omitting them.
--
-- ALL MODULES ARE LIVE: purely additive, every default preserves today's behaviour (D4/D5).
-- Idempotent (D7): information_schema-guarded throughout.

-- ── 1. AR: the invoice as issued ────────────────────────────────────────────────────────────
-- Back-filled to the CURRENT grand_total, which is what the statement already shows -- so every existing
-- invoice renders byte-identical to today. Rows where a return already happened are therefore back-filled
-- to their netted value, and that is intentional: it is the only value we still have.
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='customer_history' AND COLUMN_NAME='issued_total')=0,
    'ALTER TABLE customer_history ADD COLUMN issued_total DECIMAL(19,2) DEFAULT NULL', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

UPDATE customer_history SET issued_total = grand_total WHERE issued_total IS NULL;

-- ── 2. AR: the credit note's face value ─────────────────────────────────────────────────────
-- Deliberately NOT back-filled (see the header note). NULL means "issued before this migration" and the
-- read path skips those lines -- which is exactly what keeps pre-V34 balances identical.
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='sale_return' AND COLUMN_NAME='credit_amount')=0,
    'ALTER TABLE sale_return ADD COLUMN credit_amount DECIMAL(19,2) DEFAULT NULL', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- The statement collects a customer's notes by the invoices it already loaded; sale_return carries no
-- customer_id, so invoice_no is the join key and the lookup is one batched IN, never per row.
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='sale_return' AND INDEX_NAME='idx_sale_return_org_invoice')=0,
    'CREATE INDEX idx_sale_return_org_invoice ON sale_return (organization_id, invoice_no)', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- ── 3. AP: the bill as issued ───────────────────────────────────────────────────────────────
-- Fully reconstructable, so it IS back-filled: issued gross = what remains (goods + input tax) plus every
-- debit note already raised against the bill. GROSS on both sides -- purchase_return.amount is gross, and
-- gross is the basis Purchase.dueAmount already settles on (paid - (total_amount + tax_amount)).
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='purchase' AND COLUMN_NAME='issued_total')=0,
    'ALTER TABLE purchase ADD COLUMN issued_total DECIMAL(19,2) DEFAULT NULL', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

UPDATE purchase p
SET p.issued_total = COALESCE(p.total_amount, 0) + COALESCE(p.tax_amount, 0)
                   + COALESCE((SELECT SUM(r.amount) FROM purchase_return r
                               WHERE r.purchase_id = p.purchase_id), 0)
WHERE p.issued_total IS NULL;

-- No index needed for the vendor's debit notes: V33 already created
-- idx_purchase_return_org (organization_id, vender_id), which is exactly the statement's lookup.
