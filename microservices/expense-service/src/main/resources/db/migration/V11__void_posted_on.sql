-- EX-8a — the date a void's reversal is posted under (the person's own day, TenantClock), kept on the voucher so the
-- expense report counts the void on the same day the P&L does. voided_at is the server's clock and can fall on another
-- day near midnight. Older voids are back-filled from voided_at: right except for a void made within the zone offset of
-- midnight (stated in slices/ex-8a-expense-report.md).
ALTER TABLE expense_voucher ADD COLUMN void_posted_on DATE NULL;
UPDATE expense_voucher SET void_posted_on = DATE(voided_at) WHERE status = 'VOIDED' AND voided_at IS NOT NULL;
