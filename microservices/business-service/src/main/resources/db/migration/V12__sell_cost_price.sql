-- SF-10: per-line cost (COGS) snapshot on the sell line, so the Sale Detail Report can show true margin
-- (margin = net_amount − cost_price × quantity). Populated at sale time from the product's latest purchase rate;
-- legacy rows stay NULL (margin shows blank for them). Nullable, no backfill — new sales fill it going forward.
ALTER TABLE sell ADD COLUMN cost_price DECIMAL(19,2) NULL;
