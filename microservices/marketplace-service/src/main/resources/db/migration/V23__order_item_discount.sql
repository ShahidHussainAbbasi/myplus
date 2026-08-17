-- A per-line concession on a booked order.
--
-- WHY: a distribution rep negotiates product by product -- the shop takes the saline at list price and argues
-- over the Ringer. The order line could carry only a PRICE, so the rep's only way to give 5% on one item was
-- to overwrite that price. That works arithmetically and destroys the information: the invoice then shows a
-- lower trade price instead of "list, less discount", the shopkeeper cannot see what they were given, and
-- "what did we discount this month" has no answer because no discount was ever recorded.
--
-- The whole-document customer_history.trade_discount (V35 on the business side) does not cover this: it is one
-- concession at the foot of the invoice, not one per product.
--
-- WHY AN AMOUNT AND NOT A PERCENTAGE: the invoice side (sell.discount) takes an amount, and the sale path
-- already resolves and applies it -- including taxing the DISCOUNTED base. Storing a percentage here would put
-- a second opinion on the wire about what it is a percentage of. The booking screen accepts either and
-- converts before sending.
--
-- NULL on every existing line, which is correct: no booked order before this carried a line discount, and a
-- null leaves the order total exactly what it was.
--
-- Idempotent so a re-run on a partially migrated database is safe.

SET @ddl := (
  SELECT IF(
    EXISTS(SELECT 1 FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE()
             AND TABLE_NAME   = 'order_items'
             AND COLUMN_NAME  = 'discount'),
    'SELECT "order_items.discount already present"',
    'ALTER TABLE order_items ADD COLUMN discount DECIMAL(19,2) NULL AFTER price'
  )
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
