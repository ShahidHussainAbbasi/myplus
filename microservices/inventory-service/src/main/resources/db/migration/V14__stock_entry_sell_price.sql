-- PR-3b — the price THIS batch sells at, written by its purchase when the business sells each purchase at its own price
-- (pos.pricing.purchaseMode = per_batch). NULL = the product's price: every batch received before this, and every batch
-- of a business that does not sell per batch. No backfill — guessing an old batch's price would invent a price nobody set.
ALTER TABLE stock_entries ADD COLUMN sell_price DECIMAL(19,2) NULL;
