-- CN-1 — a credit note must print what the CUSTOMER bought, and must still print it after a full return.
--
-- TWO DEFECTS, ONE CAUSE: sale_return records only the SHELF figure.
--
-- 1. A loose return printed the shelf fraction. Return 3 tablets from a box of 40 and the document the customer
--    takes away reads "0.075 × 311.60" instead of "3 tablets × 7.79". U13 fixed the return DIALOG and the sale
--    grid; the printed credit note was never converted, and it is the half the customer keeps. Live in dev:
--    CRN-000038, quantity 0.075.
--
-- 2. ⚠ EVERY fully-returned credit note prints with NO RATE AT ALL — pack sales included, not just loose ones.
--    SellController.creditNote passes `sold != null ? sold.getSellRate() : null`, and a FULL return deletes the
--    sell row (`sellService.deleteById`). So the rate is resolved from a row that no longer exists. The register
--    and the printed note share one mapper, so both are affected.
--
-- WHY A SNAPSHOT AND NOT A LOOKUP: after a full return there is nothing left to look up. The loose view lives on
-- the Sell line (soldUnit / soldQuantity / soldRate / packSizeSnapshot) and that line is deleted. A document must
-- be reproducible years later from its own row — the same reason Sell.packSizeSnapshot exists, and the same
-- reason issuedTotal is captured on the invoice header before a return re-settles it.
--
-- Nullable, and NOT back-filled: rows written before this migration genuinely cannot be reconstructed (the sell
-- line may be gone). The mapper falls back to today's behaviour for them — the shelf quantity, and whatever rate
-- the sell row can still supply — rather than printing a blank or inventing a number.
--
-- Guarded ADD COLUMNs (the V35/V54 pattern in this module): a re-run on a database that already has them does
-- nothing, so the migration is safe on every environment including one restored from an older dump.

SET @s := (SELECT IF(EXISTS(SELECT 1 FROM information_schema.columns
                            WHERE table_schema = DATABASE() AND table_name = 'sale_return'
                              AND column_name = 'sold_unit'),
                    'DO 0',
                    'ALTER TABLE sale_return ADD COLUMN sold_unit VARCHAR(16) DEFAULT NULL'));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- The PIECES returned (3 tablets), not the shelf fraction. FLOAT to match Sell.soldQuantity, which is the
-- figure it is copied from — a different type here would make the two views disagree at the edges.
SET @s := (SELECT IF(EXISTS(SELECT 1 FROM information_schema.columns
                            WHERE table_schema = DATABASE() AND table_name = 'sale_return'
                              AND column_name = 'sold_quantity'),
                    'DO 0',
                    'ALTER TABLE sale_return ADD COLUMN sold_quantity FLOAT DEFAULT NULL'));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- Price per PIECE at the time of sale (7.79), copied from Sell.soldRate.
SET @s := (SELECT IF(EXISTS(SELECT 1 FROM information_schema.columns
                            WHERE table_schema = DATABASE() AND table_name = 'sale_return'
                              AND column_name = 'sold_rate'),
                    'DO 0',
                    'ALTER TABLE sale_return ADD COLUMN sold_rate DECIMAL(19,2) DEFAULT NULL'));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- The pack size THAT APPLIED AT THE SALE. A product's pack size can be edited afterwards; a document that
-- re-derived it would silently restate an old note.
SET @s := (SELECT IF(EXISTS(SELECT 1 FROM information_schema.columns
                            WHERE table_schema = DATABASE() AND table_name = 'sale_return'
                              AND column_name = 'pack_size_snapshot'),
                    'DO 0',
                    'ALTER TABLE sale_return ADD COLUMN pack_size_snapshot INT DEFAULT NULL'));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- The SHELF-unit rate (the pack price). This is defect 2's fix and it applies to EVERY return, loose or not:
-- with the sell row deleted there is otherwise no rate to print.
SET @s := (SELECT IF(EXISTS(SELECT 1 FROM information_schema.columns
                            WHERE table_schema = DATABASE() AND table_name = 'sale_return'
                              AND column_name = 'unit_rate'),
                    'DO 0',
                    'ALTER TABLE sale_return ADD COLUMN unit_rate DECIMAL(19,2) DEFAULT NULL'));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
