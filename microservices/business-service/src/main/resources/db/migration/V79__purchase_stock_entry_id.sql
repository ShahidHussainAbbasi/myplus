-- PR-3b — the inventory batch (stock_entries.id) this purchase line booked in, returned by the stock import. Lets an
-- edit re-price exactly that batch when the business sells per batch — a batch number is optional and often blank, so
-- it cannot identify the batch. NULL on purchases made before this column and when inventory was not reached.
ALTER TABLE purchase ADD COLUMN stock_entry_id BIGINT NULL;
