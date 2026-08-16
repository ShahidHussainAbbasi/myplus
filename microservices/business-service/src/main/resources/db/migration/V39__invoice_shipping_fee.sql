-- Delivery charged to the customer, on the invoice header.
--
-- WHY: a storefront order's delivery fee was charged to the shopper and stored on the marketplace order, but
-- the sale contract had nowhere to carry it, so it never reached the books. Delivery income was absent from
-- the P&L entirely, and the order's own total disagreed with the invoice it pointed at.
--
-- WHY a column and not "just add it to a line": delivery is not goods. Folding it into the goods subtotal
-- would overstate revenue, and putting it through the line path would drag it into the TAX BASE — the
-- storefront quote adds the fee after tax and does not tax it, so taxing it here would put the quote and the
-- invoice straight back into disagreement.
--
-- It posts to 4300 Delivery Income (added to finance's default chart of accounts in the same change, and
-- backfilled into existing orgs by GlService.ensureDefaults the way 2200 Store Credit and 4200 Sales Discount
-- were).
--
-- NULL on every existing row, which is correct: no invoice raised before this carried a delivery charge.
-- Idempotent so a re-run on a partially migrated database is safe.

SET @ddl := (
  SELECT IF(
    EXISTS(SELECT 1 FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE()
             AND TABLE_NAME   = 'customer_history'
             AND COLUMN_NAME  = 'shipping_fee'),
    'SELECT "customer_history.shipping_fee already present"',
    'ALTER TABLE customer_history ADD COLUMN shipping_fee DECIMAL(19,2) NULL AFTER trade_discount'
  )
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
