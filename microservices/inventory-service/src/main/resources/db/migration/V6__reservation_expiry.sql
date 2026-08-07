-- OMS O5a — give a stock hold a deadline.
--
-- Before this, a RESERVED reservation held stock forever. Availability is computed as
-- (quantity - reserved_quantity), so a hold that was never confirmed or released did not delay a sale — it
-- removed the stock from sale permanently, while leaving it counted in on-hand. SagaSellService even logs
-- "held stock will lapse/cleanup later" on a failed compensating release; nothing lapsed and nothing cleaned up,
-- because neither a deadline nor a sweeper existed.
--
-- Two changes, both idempotent (MySQL 8 has no ADD COLUMN IF NOT EXISTS), same guarded pattern as V4.

-- 1) The deadline itself. NULL means "no deadline" and is the honest value for every row written before this
--    migration: we cannot know when those holds were meant to lapse, and inventing a retrospective expiry would
--    have the sweeper release historical holds en masse on first run. They are dealt with deliberately, by an
--    operator, via the manual sweep endpoint.
SET @sql := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='reservations' AND COLUMN_NAME='expires_at')=0,
    'ALTER TABLE reservations ADD COLUMN expires_at datetime(6) NULL AFTER updated_at', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 2) The new status. `status` is a real MySQL ENUM, so adding a Java enum constant is NOT enough — writing
--    EXPIRED without this fails at runtime with "Data truncated for column 'status'". EXPIRED is kept distinct
--    from RELEASED on purpose: "the caller compensated" and "nobody ever came back" are different facts, and
--    only the second indicates a defect upstream worth chasing.
SET @sql := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='reservations'
                  AND COLUMN_NAME='status' AND COLUMN_TYPE LIKE '%EXPIRED%')=0,
    'ALTER TABLE reservations MODIFY COLUMN status enum(''CONFIRMED'',''OUT_OF_STOCK'',''RELEASED'',''RESERVED'',''EXPIRED'') DEFAULT NULL',
    'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 3) The sweeper's only query is "RESERVED rows past their deadline". Without this it is a full scan of every
--    reservation the platform has ever taken, on a schedule, forever.
SET @sql := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='reservations' AND INDEX_NAME='idx_resv_status_expires')=0,
    'CREATE INDEX idx_resv_status_expires ON reservations (status, expires_at)', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
