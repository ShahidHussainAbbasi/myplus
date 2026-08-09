-- OMS O5d — was this line SCANNED into the parcel, or typed?
--
-- O5b made a dispatch recordable but not verifiable: the Ship form defaults to "everything outstanding", so the
-- fastest correct-LOOKING action is to accept the defaults whether or not that is what physically went in the
-- box. Every O5b guard checks the quantities are arithmetically valid; none can tell whether the goods are the
-- right goods.
--
-- Recording HOW a line was entered is what makes the difference auditable. A shop can then answer "was this
-- parcel actually checked?" — which matters when a customer says the wrong thing arrived.
--
-- Defaults to 0 (unverified) and every pre-O5d row keeps that, which is the honest value: those lines were
-- typed, because scanning did not exist yet. Backfilling them to 1 would claim a verification that never
-- happened.

SET @sql := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='shipment_line' AND COLUMN_NAME='verified')=0,
    'ALTER TABLE shipment_line ADD COLUMN verified TINYINT(1) NOT NULL DEFAULT 0 AFTER quantity', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
