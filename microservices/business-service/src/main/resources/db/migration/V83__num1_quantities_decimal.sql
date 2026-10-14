-- NUM-1 (2026-10-14) — quantities and one money column leave FLOAT. Standard D14 (SAAS-BUILD-STANDARDS §1b).
-- Design: microservices/docs/slices/num-1-float-to-decimal.md
--
-- WHY
-- A FLOAT cannot hold 0.075 (three tablets of a box of forty): it stores 0.0750000030, and every sum, return and
-- hand-off to inventory carries the error. Measured on the live Docker DB before writing this: 33 sell rows and 26
-- sale_return rows held such values — exactly the shelf-fraction columns; every whole-number column was clean.
--
-- WHAT IT DOES TO THE DATA
-- FLOAT -> DECIMAL rounds each value to the new scale. Pre-flight (2026-10-14): the largest change is 0.000000024
-- (sell.quantity) and 0.000000006 (sale_return.quantity); every affected value is a float stand-in for a clean
-- number (0.2, 0.175, 0.3, 1.4, 0.05) and becomes exactly that number. Largest value 630 — far inside 15 integer
-- digits. purchase.purchase_expense is NULL on every row (no reader); it is converted to the money type, not dropped.
--
-- Idempotent: MODIFY on a column that is already DECIMAL(19,4) changes nothing. Nullability is kept as it is
-- (every one of these columns is NULL-able today).
-- No placeholder syntax anywhere in this file (Flyway would substitute it, even in a comment).
--
-- ⚠ MODIFY COLUMN rebuilds the table (sell 3,359 rows, purchase 2,961) — a deploy window, not a trading hour.

ALTER TABLE sell
    MODIFY COLUMN quantity          DECIMAL(19,4) NULL,
    MODIFY COLUMN sold_quantity     DECIMAL(19,4) NULL,
    MODIFY COLUMN bonus_quantity    DECIMAL(19,4) NULL;

ALTER TABLE purchase
    MODIFY COLUMN quantity          DECIMAL(19,4) NULL,
    MODIFY COLUMN bonus_quantity    DECIMAL(19,4) NULL,
    MODIFY COLUMN purchase_expense  DECIMAL(19,2) NULL;

ALTER TABLE sale_return
    MODIFY COLUMN quantity          DECIMAL(19,4) NULL,
    MODIFY COLUMN sold_quantity     DECIMAL(19,4) NULL;

ALTER TABLE sales_quote_line
    MODIFY COLUMN quantity          DECIMAL(19,4) NULL,
    MODIFY COLUMN sold_quantity     DECIMAL(19,4) NULL;
