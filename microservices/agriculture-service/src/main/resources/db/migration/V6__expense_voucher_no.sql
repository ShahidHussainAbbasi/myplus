-- EX-9b: a farm expense row brought into the books (owner-run import, R-4) carries the EXP- number expense-service gave
-- it. NULL = never imported. A row with a number is in the books: it is no longer listed for import and is never
-- hard-deleted (F3: a mistake is voided in Expenses, with a reason).
ALTER TABLE agriculture_expense ADD COLUMN expense_voucher_no VARCHAR(20) NULL;
