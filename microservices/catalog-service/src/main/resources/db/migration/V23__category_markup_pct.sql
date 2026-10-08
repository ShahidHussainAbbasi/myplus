-- PR-2b — a category's markup percentage, between the product's own (wins) and the business's (fallback) when the
-- markup rule suggests a selling price from a purchase's cost. NULL = use the business's. 14.50 means 14.5 %.
ALTER TABLE categories ADD COLUMN markup_pct DECIMAL(7,2) NULL;
