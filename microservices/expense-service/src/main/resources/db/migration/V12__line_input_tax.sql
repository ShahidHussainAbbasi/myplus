-- EX-8d — the recoverable input tax inside a line's amount (0 unless the business switched expense.tax.inputRecoverable
-- on). The amount stays what was paid; the expense account gets amount − tax_amount, and 2100 gets tax_amount.
ALTER TABLE expense_voucher_line ADD COLUMN tax_amount DECIMAL(19,2) NOT NULL DEFAULT 0;
