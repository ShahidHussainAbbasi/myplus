-- Seed the Hibernate TableGenerator "hi/lo" sequence tables.
--
-- V1__baseline.sql CREATEs the *_seq tables (next_val BIGINT) but never inserts the required seed
-- row, and the service runs with ddl-auto=validate (config-server), so Hibernate does not auto-seed
-- them. On a fresh DB the very first insert then fails with:
--   IdentifierGenerationException: could not read a hi value - you need to populate the table: <x>_seq
--
-- Fix: give each table exactly one row = MAX(pk)+1 of its backing entity table(s).
--   * fresh DB     -> MAX is NULL -> next_val = 1 (identical to what Hibernate would have seeded)
--   * existing DB  -> next_val = highest id + 1, so no PK collision. All these generators use
--                     allocationSize=1, so there is no pooled gap to preserve; DELETE+reset is safe.
-- Idempotent by construction (Flyway applies it once; DELETE first tolerates any pre-existing row).

-- vender_seq  <- vender.vender_id
DELETE FROM vender_seq;
INSERT INTO vender_seq (next_val) SELECT COALESCE(MAX(vender_id), 0) + 1 FROM vender;

-- item_type_seq  <- item_type.item_type_id
DELETE FROM item_type_seq;
INSERT INTO item_type_seq (next_val) SELECT COALESCE(MAX(item_type_id), 0) + 1 FROM item_type;

-- item_unit_seq  <- item_unit.item_unit_id
DELETE FROM item_unit_seq;
INSERT INTO item_unit_seq (next_val) SELECT COALESCE(MAX(item_unit_id), 0) + 1 FROM item_unit;

-- purch_seq  <- purchase.purchase_id
DELETE FROM purch_seq;
INSERT INTO purch_seq (next_val) SELECT COALESCE(MAX(purchase_id), 0) + 1 FROM purchase;

-- sell_seq  <- sell.sell_id
DELETE FROM sell_seq;
INSERT INTO sell_seq (next_val) SELECT COALESCE(MAX(sell_id), 0) + 1 FROM sell;

-- cust_seq  <- shared generator: customer.customer_id AND customer_history.customer_history_id
DELETE FROM cust_seq;
INSERT INTO cust_seq (next_val)
SELECT GREATEST(
         (SELECT COALESCE(MAX(customer_id), 0)         FROM customer),
         (SELECT COALESCE(MAX(customer_history_id), 0) FROM customer_history)
       ) + 1;
