-- Flyway: inventory schema added after the V1 baseline (created by ddl-auto in dev) — needed on fresh/prod
-- (ddl-auto:validate). All idempotent: CREATE TABLE IF NOT EXISTS + information_schema-guarded ADD COLUMN, so a
-- re-run on the existing dev DB is a no-op. DDL mirrors the live Hibernate-generated schema so `validate` accepts it.
--   * reservations / reservation_picks — sell↔stock saga (slice 33)
--   * stock_levels                     — per-product on-hand (slice 33)
--   * stock_entries.reserved_quantity  — saga hold (slice 33)
--   * stock_entries.restockable        — P11 quarantine (slice 55)
SET FOREIGN_KEY_CHECKS=0;

CREATE TABLE IF NOT EXISTS reservations (
  id bigint NOT NULL AUTO_INCREMENT,
  created_at datetime(6) DEFAULT NULL,
  idempotency_key varchar(255) DEFAULT NULL,
  organization_id bigint DEFAULT NULL,
  reservation_id varchar(255) NOT NULL,
  status enum('CONFIRMED','OUT_OF_STOCK','RELEASED','RESERVED') DEFAULT NULL,
  updated_at datetime(6) DEFAULT NULL,
  user_id bigint DEFAULT NULL,
  user_type varchar(255) DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_resv_reservation_id (reservation_id),
  UNIQUE KEY uq_resv_org_idem (organization_id, idempotency_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS reservation_picks (
  id bigint NOT NULL AUTO_INCREMENT,
  batch_no varchar(255) DEFAULT NULL,
  expiry_date date DEFAULT NULL,
  product_id bigint DEFAULT NULL,
  quantity float DEFAULT NULL,
  stock_entry_id bigint DEFAULT NULL,
  reservation_ref bigint DEFAULT NULL,
  returned_quantity float DEFAULT NULL,
  PRIMARY KEY (id),
  KEY idx_respick_resv (reservation_ref),
  CONSTRAINT fk_respick_resv FOREIGN KEY (reservation_ref) REFERENCES reservations (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS stock_levels (
  id bigint NOT NULL AUTO_INCREMENT,
  cost_price decimal(38,2) DEFAULT NULL,
  created_at datetime(6) DEFAULT NULL,
  current_stock float DEFAULT NULL,
  max_stock_level float DEFAULT NULL,
  min_stock_level float DEFAULT NULL,
  organization_id bigint DEFAULT NULL,
  product_id bigint NOT NULL,
  reorder_point float DEFAULT NULL,
  updated_at datetime(6) DEFAULT NULL,
  user_id bigint DEFAULT NULL,
  user_type varchar(255) DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_stocklevel_org_product (organization_id, product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='stock_entries' AND COLUMN_NAME='reserved_quantity')=0,
  'ALTER TABLE stock_entries ADD COLUMN reserved_quantity float DEFAULT NULL', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='stock_entries' AND COLUMN_NAME='restockable')=0,
  'ALTER TABLE stock_entries ADD COLUMN restockable bit(1) DEFAULT NULL', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET FOREIGN_KEY_CHECKS=1;
