-- FP-3b (E10) — a bill payment can be reversed. The row stays (history), marked REVERSED, with the books' mirror
-- number (PV-…-R), who reversed it, when and why.
ALTER TABLE expense_bill_payment
    ADD COLUMN reversal_receipt_no VARCHAR(24)  NULL,
    ADD COLUMN reversal_reason     VARCHAR(255) NULL,
    ADD COLUMN reversed_by         BIGINT       NULL,
    ADD COLUMN reversed_at         DATETIME     NULL;
