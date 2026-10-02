-- DR-1 (decision D5) — an optional CNIC / NTN on a supplier.
--
-- WHY: a customer already carries a CNIC, a supplier carried none, so the strongest key for recognising that a
-- supplier is a business already registered as a customer (same legal identity, different phone) was missing on
-- one side. Sent to party-service as the tax key; never required.
--
-- Additive and nullable: no existing row changes. Guarded so a re-run (or a database where it was added by hand)
-- is a no-op.

SET @col_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
                     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'vender' AND COLUMN_NAME = 'cnic_ntn');
SET @ddl := IF(@col_exists = 0,
               'ALTER TABLE vender ADD COLUMN cnic_ntn VARCHAR(32) NULL',
               'DO 0');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
