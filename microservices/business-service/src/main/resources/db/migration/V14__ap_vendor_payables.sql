-- F1 (AP subledger): vendor payables. Adds the vendor↔purchase link + per-bill paid/due + the vendor running
-- payable, then SEEDS EXISTING HISTORY AS SETTLED (paid = net, due = 0, vendor due = 0) so no vendor suddenly
-- shows a huge historical payable — payables accrue only on new/edited on-credit purchases going forward.
-- Sign convention mirrors AR (CustomerHistory.dueAmount = paid − bill): purchase.due_amount = paid − net,
-- negative while we still owe the vendor; vendor.due_amount = −Σ(open purchase due), floored at 0.

ALTER TABLE vender   ADD COLUMN due_amount  DECIMAL(19,2) NULL;
ALTER TABLE purchase ADD COLUMN vender_id   BIGINT        NULL;
ALTER TABLE purchase ADD COLUMN paid_amount DECIMAL(19,2) NULL;
ALTER TABLE purchase ADD COLUMN due_amount  DECIMAL(19,2) NULL;

-- Seed history as settled (idempotent by the IS NULL guards).
UPDATE purchase SET paid_amount = COALESCE(net_amount, 0), due_amount = 0 WHERE paid_amount IS NULL;
UPDATE vender   SET due_amount  = 0 WHERE due_amount IS NULL;

-- Perf: the AP FIFO (findOpenPurchasesByVendor: vender_id + due_amount<0) and sumDueByVendor (vender_id) both
-- filter on vender_id; index it (+ due_amount) so payVendor/recomputePayable stay fast as purchase history grows.
CREATE INDEX idx_purchase_vender ON purchase (vender_id, due_amount);
