-- MKT-2f — bank holidays for the settlement calendar (slice doc mkt-2f-settlement-reports.md). A line becomes payable
-- T+N BUSINESS days after its return window closes (MKT-1g); until now only Saturday and Sunday were skipped.
-- One row per holiday, set by the operator. Idempotent (D7).
CREATE TABLE IF NOT EXISTS mkt_holiday (
  holiday_date        DATE          NOT NULL,
  name                VARCHAR(80)   NOT NULL,
  created_by_user_id  BIGINT        DEFAULT NULL,
  created_at          DATETIME(6)   NOT NULL,
  PRIMARY KEY (holiday_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- The settlement report reads the ledger by period (effective_at); V31 indexes it only by seller and by order line.
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = 'mkt_settlement_entry' AND INDEX_NAME = 'idx_mkt_entry_effective') = 0,
  'CREATE INDEX idx_mkt_entry_effective ON mkt_settlement_entry (effective_at, organization_id)', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
