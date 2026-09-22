-- U15-C — THE SHOP NAMES THE UNIT IT BUYS IN.
--
-- THE COLLISION THIS ENDS
-- A pharmacy registers "a box of 40 tablets", so `unit` = "box". The purchase screen then offered a
-- Pack | Box toggle in which "Pack" meant the shop's BOX and "Box" meant a CARTON of N boxes — a level the
-- product record had never heard of. One word, two meanings, on the same form. Reported by the user
-- (2026-09-21): "pack vs box get confused".
--
-- WHY A COLUMN AND NOT A BETTER WORD
-- The first proposal was to rename our hardcoded "Box" to "Carton". The user refused it with the right
-- question — that still leaves three levels and a word every shop must learn. Research settled it: SAP
-- (order unit / sales unit), Odoo (Purchase UoM), Tally (alternate unit + conversion factor) and Marg ERP
-- (Unit-1 / Unit-2) all own the ROLES and let the customer own the WORDS. Only a single-vertical product
-- (POS Nation: case/pack/single) can hardcode nouns, because every one of its customers is a liquor store.
-- U1 already does this for the piece ("one piece is a tablet"); the carton was the one level where we did
-- not, and that inconsistency was the defect — not the word.
--
-- ⚠ NULLABLE, AND THAT IS THE COMMON CASE
-- Blank means this shop does not buy in multiples, and the purchase toggle then does not render at all.
-- Most shops end up with a SIMPLER purchase screen than before, where the toggle was always visible whether
-- or not the shop had ever bought a carton. No backfill: a NULL here is a real answer, not missing data.
--
-- ⚠ THE COUNT IS A HINT, NEVER A DEFAULT — U5's ruling stands
-- purchase_pack_count records what a multiple USUALLY holds, and the purchase form may only SHOW it
-- ("usually 12"). It must never pre-fill packsPerBox: U5 deliberately makes that factor typed every time,
-- because "box sizes vary by shipment, and a stale default would be silently wrong for this delivery with
-- the confidence of a pre-filled field behind it". Pre-filling would re-open the tenfold cost error U5
-- exists to prevent.
--
-- The WIRE is unchanged: purchaseUnit='BOX', packsPerBox and PurchaseService.convertBoxesToPacks keep their
-- names and values. Stored identifiers carry no meaning for a shopkeeper; only the words on screen change.
--
-- Idempotent in V11's idiom: dev runs ddl-auto:update and may already have added these from the entity.

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='products' AND COLUMN_NAME='purchase_unit_name')=0,
    'ALTER TABLE products ADD COLUMN purchase_unit_name VARCHAR(32) NULL', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='products' AND COLUMN_NAME='purchase_pack_count')=0,
    'ALTER TABLE products ADD COLUMN purchase_pack_count INT NULL', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
