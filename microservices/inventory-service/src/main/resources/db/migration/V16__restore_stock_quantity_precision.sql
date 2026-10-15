-- NUM-1 (2026-10-14) — put back the DECIMAL(19,4) that V8 gave every stock quantity. Standard D14.
--
-- WHAT HAPPENED
-- V8 set these ten columns to DECIMAL(19,4) (applied 2026-08-26). On the live Docker DB they were DECIMAL(38,2)
-- again, with V8's defaults gone — and every column whose ENTITY declared a precision was still right, while every
-- one that did not was exactly Hibernate's default (38,2). So the tables had been regenerated from the entities.
-- Which boot did it is not knowable (no logs); the entities now carry @Column(precision = 19, scale = 4), so a
-- regeneration produces the right type.
--
-- WHAT IT COST
-- A loose sale of 3 tablets from a box of 40 is 0.075 of a box. The reservation pick stored 0.08, the confirm
-- re-read that pick and took 0.08 off the shelf: 3.2 tablets left the books for every 3 sold (gate NUM-1 N1:
-- 4.92 boxes where 4.925 were left). A third of a box (1 tablet of 3) stored 0.33 / 0.67.
--
-- WHAT THIS DOES
-- Widens 2 decimals to 4: no stored value changes (every DECIMAL(38,2) value is exact in DECIMAL(19,4); the
-- largest stock value is far below 15 integer digits). Values already rounded by the old type stay as they are —
-- the exact figure was never stored, so it cannot be recovered.
-- MODIFY is idempotent: re-running it on a correct table changes nothing.
--
-- ⚠ MODIFY COLUMN takes a brief table lock — a deploy window, not the middle of a trading day.

ALTER TABLE stock_entries
    MODIFY COLUMN quantity          DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    MODIFY COLUMN reserved_quantity DECIMAL(19,4)     NULL DEFAULT 0.0000;

ALTER TABLE stock_levels
    MODIFY COLUMN current_stock     DECIMAL(19,4)     NULL DEFAULT 0.0000,
    MODIFY COLUMN min_stock_level   DECIMAL(19,4)     NULL,
    MODIFY COLUMN max_stock_level   DECIMAL(19,4)     NULL,
    MODIFY COLUMN reorder_point     DECIMAL(19,4)     NULL;

ALTER TABLE reservation_picks
    MODIFY COLUMN quantity          DECIMAL(19,4)     NULL,
    MODIFY COLUMN returned_quantity DECIMAL(19,4)     NULL DEFAULT 0.0000;

ALTER TABLE stock_adjustments
    MODIFY COLUMN quantity          DECIMAL(19,4) NOT NULL;

ALTER TABLE stock_transfers
    MODIFY COLUMN quantity          DECIMAL(19,4) NOT NULL;
