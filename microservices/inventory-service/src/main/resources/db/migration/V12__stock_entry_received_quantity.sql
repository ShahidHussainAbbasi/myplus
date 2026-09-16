-- COGS-1 — the quantity a batch was RECEIVED with, so its unit cost stops rising as it sells down.
-- Design: microservices/docs/slices/cogs-1-batch-unit-cost.md
--
-- THE DEFECT THIS FIXES (money):
-- ReservationService.unitCostOf allocated a batch's cost as  paid_total / quantity, and `quantity` is what is LEFT —
-- it falls on every sale while paid_total (what the whole batch cost) never changes. So every sale after the first
-- from a batch was costed higher than the one before:
--     10 packs bought for 800.00
--     sale 1 (2 packs):   800 / 10 = 80.00   correct
--     sale 2 (0.5 pack):  800 /  8 = 100.00  should be 80.00
-- On the live dev data one batch went from 500 to 10,000 per unit over 14 sales. Cost of goods was overstated and
-- Inventory understated by the same amount — in the SAME journal, so the trial balance stayed perfectly balanced and
-- nothing looked wrong. stock_entries.paid_total's own comment always said the divisor was the batch's size.
--
-- received_quantity is that size: set once, when the batch is received, and never changed.
--
-- BACKFILL — only the batches that carry a paid_total (the others cost from purchase_price and were never affected).
-- What a batch was received with = what it holds now + what CONFIRMED sales took from it − what came back:
--     only CONFIRMED reservations ever decrement a batch (reserve holds, release/expire restore the hold);
--     returns restore the batch AND increment returned_quantity, so they cancel out here.
-- Verified against the dev database before writing this (2026-09-16), on all 672 such batches:
--     398 never sold (received = current),
--     274 sold from — for EVERY one, this rebuild equals paid_total / the unit cost of the batch's FIRST pick
--         (the one pick the defect could not have touched), an independent second derivation;
--     0 disagree.
-- ⚠ One thing it cannot see: a stock correction (+/−) aimed at a specific batch is not recorded per batch. No
-- disagreement in the check above says none of the sold-from batches had one; an unsold batch that did would carry
-- its corrected quantity — which is still the best figure available, and never worse than the old divisor.
--
-- Idempotent: information_schema-guarded ADD COLUMN, and the backfill only fills rows that are still NULL, so a
-- re-run — or a dev database where ddl-auto already added the column — changes nothing that was already set.

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='stock_entries' AND COLUMN_NAME='received_quantity')=0,
    'ALTER TABLE stock_entries ADD COLUMN received_quantity DECIMAL(19,4) DEFAULT NULL', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

UPDATE stock_entries e
LEFT JOIN (
    SELECT rp.stock_entry_id,
           SUM(rp.quantity - COALESCE(rp.returned_quantity, 0)) AS net_out
    FROM reservation_picks rp
    JOIN reservations r ON r.id = rp.reservation_ref
    WHERE r.status = 'CONFIRMED'
    GROUP BY rp.stock_entry_id
) consumed ON consumed.stock_entry_id = e.id
SET e.received_quantity = e.quantity + COALESCE(consumed.net_out, 0)
WHERE e.paid_total IS NOT NULL
  AND e.received_quantity IS NULL;
