-- OMS O7 D2c — WHICH outlet a booked order is for.
--
-- `orders` recorded the buyer as a NAME and nothing else. For the storefront that is correct: a web shopper is
-- genuinely a new person, and business-service resolving-or-creating them from name + contact is the right
-- behaviour. For a FIELD order it is wrong in a way that quietly corrupts a distributor's books.
--
-- The failure, traced: with no customer id, `CustomerService.saveUpdateCustomer` falls to Query-By-Example, and
-- the probe is built AFTER `setUserId(actor)` — so it matches on name + contact + THE ACTING USER. The outlet
-- was created by the owner; the dispatch runs as the warehouse admin. The probe cannot match, so a SECOND
-- "Irfan Medical Store" is created with no credit limit and a zero balance. The invoice then bills the
-- duplicate, the outlet's receivable is split across two rows, aging and statements disagree with reality, and
-- the credit limit the booker was shown at the counter never applies to anything.
--
-- Nullable, because it is meaningful only for orders that HAVE a trade account behind them: storefront orders
-- keep resolving by name (correct for that channel) and every historical row keeps NULL, which is the honest
-- value — nothing else can be inferred about who those orders were really for.

SET @sql := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='orders' AND COLUMN_NAME='customer_id')=0,
    'ALTER TABLE orders ADD COLUMN customer_id BIGINT NULL AFTER customer_account_id', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- "Every order for this outlet" is the read this enables (an outlet's order history on the booking screen, and
-- later its commission and coverage reports). Org first, as every scoped read on this platform is.
--
-- Deliberately NO foreign key: `customer` lives in business-service's schema, in a DIFFERENT DATABASE. A FK
-- across a service boundary is exactly the coupling the decomposition exists to prevent — this is a reference
-- by id, resolved through the trade contract, the same way product_id already works here.
SET @sql := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='orders' AND INDEX_NAME='idx_orders_org_customer')=0,
    'CREATE INDEX idx_orders_org_customer ON orders (organization_id, customer_id)', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
