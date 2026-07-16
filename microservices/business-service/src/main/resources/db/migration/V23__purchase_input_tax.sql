-- Tax register Phase B: input tax (VAT/GST credit), per-org configurable.
-- input_tax_enabled = the "Purchase tax (input credit)" toggle (independent of the "Sales tax" toggle = enabled).
-- purchase.tax_rate / tax_amount capture the input tax on a bill when the toggle is on (null/0 otherwise).

ALTER TABLE tax_setting
    ADD COLUMN input_tax_enabled bit(1) DEFAULT 0;

ALTER TABLE purchase
    ADD COLUMN tax_rate   DECIMAL(19,2) NULL,
    ADD COLUMN tax_amount DECIMAL(19,2) NULL;
