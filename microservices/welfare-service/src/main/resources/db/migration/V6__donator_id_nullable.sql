-- Allow anonymous donations (donation box / walk-in cash) when an org keeps welfare.donation.requireDonor OFF.
-- donator_id was NOT NULL in the V1 baseline; relax it so a donation may have no donor. IDEMPOTENT — a MODIFY
-- to the same definition is a safe no-op if already applied.
SET @ddl := IF(
  (SELECT IS_NULLABLE FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='donation' AND COLUMN_NAME='donator_id') = 'NO',
  'ALTER TABLE donation MODIFY donator_id BIGINT NULL',
  'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
