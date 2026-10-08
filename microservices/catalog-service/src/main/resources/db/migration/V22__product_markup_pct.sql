-- PR-2 — a product's own markup percentage, used to SUGGEST (or, in Auto, set) its selling price from what a purchase
-- cost. NULL = use the business's percentage (Settings → Purchasing). Precedence product > business; a category
-- level is reserved for PR-2b. Percent with two decimals: 14.50 means 14.5 %.
ALTER TABLE products ADD COLUMN markup_pct DECIMAL(7,2) NULL;
