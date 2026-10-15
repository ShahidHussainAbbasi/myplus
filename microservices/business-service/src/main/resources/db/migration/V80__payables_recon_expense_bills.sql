-- E11 — the daily payables check also confirms EXPENSE BILLS: what expense-service says its bills owe in the books,
-- what finance's subledger holds for them, the difference found, and how many bills were re-sent to repair it.
-- A day with an expense difference is not clean, exactly like a purchase difference.
ALTER TABLE payables_recon_day
    ADD COLUMN expense_owed     DECIMAL(19,2) NULL,
    ADD COLUMN finance_expense  DECIMAL(19,2) NULL,
    ADD COLUMN expense_diff     DECIMAL(19,2) NULL,
    ADD COLUMN bills_resent     INT           NOT NULL DEFAULT 0;
