-- U0 — stock quantities become exact decimals, holding BASE UNITS.
--
-- WHY
-- Selling loose means dividing a pack, and fractions of a pack do not divide cleanly while pieces always do:
--
--     pack of 3, sell 1 loose      packs:  0.3333...   never terminates, in FLOAT or DECIMAL
--                                  pieces: 2           exact, forever
--
-- A pack of 10 is kind to binary floats. A pack of 3, 6, 7, 12 or 24 is not — and the design's own examples
-- include crates of 24, trays of 30 and boxes of 100. Under `float`, selling the last pieces of such a pack
-- leaves a residue like 0.0000004 that no stock count will ever reconcile to zero.
--
-- This is what SAP (base UoM + MARM), Odoo (uom.uom) and Dynamics 365 BC (Quantity Base) all do, and they do
-- it for this reason.
--
-- WHY DECIMAL(19,4) AND NOT AN INTEGER
-- "Base units" reads as an integer count, and for tablets and screws it is one. But this same column holds
-- goods that are genuinely continuous — 2.5 metres of cable, 1.75 kg of produce — and those exist today. An
-- integer column would round them away SILENTLY, which is a worse defect than the one being fixed.
-- DECIMAL(19,4) is exact for every base-unit count AND still carries the continuous case.
--
-- The exactness comes from the UNIT, not from the column type: a loose sale is `5` pieces, never `0.333` packs.
--
-- WHAT THIS MIGRATION DOES *NOT* CHANGE: any value.
-- Verified before writing: 2,559 rows in stock_entries, 2,075 in stock_levels, and ZERO non-integer
-- quantities — nothing fractional can exist yet, because nothing can be sold in fractions. Every number keeps
-- the value it has. Until U1 gives a product a packSize, one base unit IS one selling unit, so this changes
-- what the column MEANS without changing what any caller sees. That is what makes U0 shippable alone.
--
-- ⚠ MODIFY COLUMN takes a brief table lock. These tables are small, but this belongs in a deploy window
-- rather than the middle of a trading day.

ALTER TABLE stock_entries
    MODIFY COLUMN quantity          DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    MODIFY COLUMN reserved_quantity DECIMAL(19,4)     NULL DEFAULT 0.0000;

ALTER TABLE stock_levels
    MODIFY COLUMN current_stock     DECIMAL(19,4)     NULL DEFAULT 0.0000,
    MODIFY COLUMN min_stock_level   DECIMAL(19,4)     NULL,
    MODIFY COLUMN max_stock_level   DECIMAL(19,4)     NULL,
    MODIFY COLUMN reorder_point     DECIMAL(19,4)     NULL;

-- The picks are what a reservation actually took from each batch. They must carry the same precision as the
-- entries they draw from, or a split allocation would round at the seam between two batches.
ALTER TABLE reservation_picks
    MODIFY COLUMN quantity          DECIMAL(19,4)     NULL,
    MODIFY COLUMN returned_quantity DECIMAL(19,4)     NULL DEFAULT 0.0000;

ALTER TABLE stock_adjustments
    MODIFY COLUMN quantity          DECIMAL(19,4) NOT NULL;

ALTER TABLE stock_transfers
    MODIFY COLUMN quantity          DECIMAL(19,4) NOT NULL;

-- ⚠ `myplusdb_inventory.products` IS DELIBERATELY LEFT ALONE.
--
-- It carries the same four level columns and looks like an obvious candidate. It is EMPTY (0 rows) and NO
-- entity maps it — and standard D5 says exactly what to do with that: "an entity-vs-table diff is a starting
-- point, NEVER evidence of a dead table", and "never drop on inference — count it in EVERY environment".
--
-- I can count dev. I cannot count production from here, and the incident D5 records is this shape precisely:
-- `myplusdb.company` looked like a dead leftover and held 336 rows nobody had migrated. Altering a table this
-- slice does not need is risk taken for tidiness.
