-- The GL outbox loses any figure it has no column for. These are the two it was losing.
--
-- gl_outbox is a PERSISTED table whose payload is copied field by field in GlOutboxService.enqueue and
-- rebuilt field by field in toReq. A value passed to enqueue with no matching column is dropped in complete
-- silence -- no error, no log, the number is simply gone by the time finance-service sees the event.
--
-- WHY discount_total: B2B D-4 added the contra-revenue rule (Dr 4200 Sales Discount) to finance and had
-- business-service pass `.discountTotal(ch.getTradeDiscount())` on every SALE. The posting rule was correct
-- and the caller was correct, but the value never crossed the outbox, so 4200 has been EMPTY in every tenant
-- since D-4 shipped. The gate at the time asserted the invoice carried the discount -- which it did -- and
-- never asserted the ledger had a 4200 line, which it did not.
--
-- WHY shipping_fee: delivery income (Cr 4300) rides inside grand_total but deliberately not inside sub_total
-- or tax_total. An event that loses it produces a journal short by exactly the fee, which GlService.validate
-- rejects -- so the sale posts NO journal at all rather than a wrong one. Loud, unlike the discount.
--
-- NULL on every existing row, which is correct: those events were delivered (or failed) long ago and are not
-- re-driven. Historic 4200 balances are NOT backfilled by this script -- see the note below.
--
-- BACKFILL: deliberately none. Re-posting historic discounts would need each affected invoice's journal
-- reversed and re-raised in the ORIGINAL period, and several of those periods are closed. In this database
-- no invoice has ever carried a trade discount, so there is nothing to recover; a deployment that does have
-- them should reconcile 4200 as an opening adjustment rather than have a migration rewrite closed books.
--
-- Idempotent so a re-run on a partially migrated database is safe.

SET @ddl := (
  SELECT IF(
    EXISTS(SELECT 1 FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE()
             AND TABLE_NAME   = 'gl_outbox'
             AND COLUMN_NAME  = 'discount_total'),
    'SELECT "gl_outbox.discount_total already present"',
    'ALTER TABLE gl_outbox ADD COLUMN discount_total DECIMAL(19,2) NULL AFTER store_credit'
  )
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl2 := (
  SELECT IF(
    EXISTS(SELECT 1 FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE()
             AND TABLE_NAME   = 'gl_outbox'
             AND COLUMN_NAME  = 'shipping_fee'),
    'SELECT "gl_outbox.shipping_fee already present"',
    'ALTER TABLE gl_outbox ADD COLUMN shipping_fee DECIMAL(19,2) NULL AFTER discount_total'
  )
);
PREPARE stmt2 FROM @ddl2;
EXECUTE stmt2;
DEALLOCATE PREPARE stmt2;
