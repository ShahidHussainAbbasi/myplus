-- Flyway: business commerce schema (slices 22/33/35/37/39/40/42/53/59), created by ddl-auto in dev + manual
-- migrations.md — needed on fresh/prod (ddl-auto:validate). The V1 baseline predates these. All idempotent:
-- CREATE TABLE IF NOT EXISTS + information_schema-guarded ADD COLUMN. DDL mirrors live Hibernate schema so
-- `validate` accepts it (incl. payment.method enum with INSURANCE from migration #4).
SET FOREIGN_KEY_CHECKS=0;

-- ---- new tables (G5 payments/shift, G3 tax, park/hold, item↔product map) ----
CREATE TABLE IF NOT EXISTS payment (
  id bigint NOT NULL AUTO_INCREMENT,
  amount decimal(19,2) DEFAULT NULL,
  customer_history_id bigint DEFAULT NULL,
  dated datetime(6) DEFAULT NULL,
  method enum('BANK_TRANSFER','CARD','CASH','CREDIT','INSURANCE','REFUND','WALLET') DEFAULT NULL,
  organization_id bigint DEFAULT NULL,
  reference varchar(255) DEFAULT NULL,
  user_id bigint DEFAULT NULL,
  PRIMARY KEY (id),
  KEY idx_payment_ch (customer_history_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS cashier_shift (
  id bigint NOT NULL AUTO_INCREMENT,
  closed_at datetime(6) DEFAULT NULL,
  counted_cash decimal(19,2) DEFAULT NULL,
  expected_cash decimal(19,2) DEFAULT NULL,
  notes varchar(255) DEFAULT NULL,
  opened_at datetime(6) DEFAULT NULL,
  opening_float decimal(19,2) DEFAULT NULL,
  organization_id bigint DEFAULT NULL,
  status enum('CLOSED','OPEN') DEFAULT NULL,
  user_id bigint DEFAULT NULL,
  variance decimal(19,2) DEFAULT NULL,
  PRIMARY KEY (id),
  KEY idx_shift_org_user_status (organization_id, user_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS cash_movement (
  id bigint NOT NULL AUTO_INCREMENT,
  amount decimal(19,2) DEFAULT NULL,
  dated datetime(6) DEFAULT NULL,
  organization_id bigint DEFAULT NULL,
  reason varchar(255) DEFAULT NULL,
  shift_id bigint DEFAULT NULL,
  type enum('DROP','PAY_IN','PAY_OUT') DEFAULT NULL,
  user_id bigint DEFAULT NULL,
  PRIMARY KEY (id),
  KEY idx_cashmove_shift (shift_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS tax_setting (
  id bigint NOT NULL AUTO_INCREMENT,
  default_rate decimal(19,2) DEFAULT NULL,
  enabled bit(1) DEFAULT NULL,
  organization_id bigint NOT NULL,
  tax_label varchar(255) DEFAULT NULL,
  tax_mode enum('EXCLUSIVE','INCLUSIVE') DEFAULT NULL,
  tax_reg_no varchar(255) DEFAULT NULL,
  updated datetime(6) DEFAULT NULL,
  user_id bigint DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_tax_setting_org (organization_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS parked_sale (
  id bigint NOT NULL AUTO_INCREMENT,
  cart_json text,
  item_count int DEFAULT NULL,
  label varchar(255) DEFAULT NULL,
  organization_id bigint DEFAULT NULL,
  parked_at datetime(6) DEFAULT NULL,
  total decimal(19,2) DEFAULT NULL,
  user_id bigint DEFAULT NULL,
  PRIMARY KEY (id),
  KEY idx_parked_org_user (organization_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS item_catalog_map (
  id bigint NOT NULL AUTO_INCREMENT,
  created_at datetime(6) DEFAULT NULL,
  item_id bigint NOT NULL,
  organization_id bigint DEFAULT NULL,
  product_id bigint NOT NULL,
  stock_migrated bit(1) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_icm_org_item (organization_id, item_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---- guarded column adds (G3 tax, G5 payment, saga, slice 23 money) ----
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='customer_history' AND COLUMN_NAME='change_amount')=0,   'ALTER TABLE customer_history ADD COLUMN change_amount decimal(19,2) DEFAULT NULL', 'DO 0');   PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='customer_history' AND COLUMN_NAME='grand_total')=0,     'ALTER TABLE customer_history ADD COLUMN grand_total decimal(19,2) DEFAULT NULL', 'DO 0');     PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='customer_history' AND COLUMN_NAME='idempotency_key')=0, 'ALTER TABLE customer_history ADD COLUMN idempotency_key varchar(255) DEFAULT NULL', 'DO 0'); PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='customer_history' AND COLUMN_NAME='payment_mode')=0,    'ALTER TABLE customer_history ADD COLUMN payment_mode varchar(255) DEFAULT NULL', 'DO 0');    PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='customer_history' AND COLUMN_NAME='reservation_id')=0,  'ALTER TABLE customer_history ADD COLUMN reservation_id varchar(255) DEFAULT NULL', 'DO 0');  PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='customer_history' AND COLUMN_NAME='saga_status')=0,     'ALTER TABLE customer_history ADD COLUMN saga_status varchar(255) DEFAULT NULL', 'DO 0');     PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='customer_history' AND COLUMN_NAME='shift_id')=0,        'ALTER TABLE customer_history ADD COLUMN shift_id bigint DEFAULT NULL', 'DO 0');              PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='customer_history' AND COLUMN_NAME='sub_total')=0,       'ALTER TABLE customer_history ADD COLUMN sub_total decimal(19,2) DEFAULT NULL', 'DO 0');       PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='customer_history' AND COLUMN_NAME='tax_total')=0,       'ALTER TABLE customer_history ADD COLUMN tax_total decimal(19,2) DEFAULT NULL', 'DO 0');       PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='customer_history' AND COLUMN_NAME='tendered_amount')=0, 'ALTER TABLE customer_history ADD COLUMN tendered_amount decimal(19,2) DEFAULT NULL', 'DO 0'); PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='sell' AND COLUMN_NAME='product_id')=0, 'ALTER TABLE sell ADD COLUMN product_id bigint DEFAULT NULL', 'DO 0');        PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='sell' AND COLUMN_NAME='tax_amount')=0, 'ALTER TABLE sell ADD COLUMN tax_amount decimal(19,2) DEFAULT NULL', 'DO 0'); PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='sell' AND COLUMN_NAME='tax_rate')=0,   'ALTER TABLE sell ADD COLUMN tax_rate decimal(19,2) DEFAULT NULL', 'DO 0');   PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='stock' AND COLUMN_NAME='batch_purchase_discount')=0, 'ALTER TABLE stock ADD COLUMN batch_purchase_discount decimal(19,2) DEFAULT NULL', 'DO 0'); PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='stock' AND COLUMN_NAME='batch_sale_discount')=0,     'ALTER TABLE stock ADD COLUMN batch_sale_discount decimal(19,2) DEFAULT NULL', 'DO 0');     PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='purchase' AND COLUMN_NAME='updated')=0, 'ALTER TABLE purchase ADD COLUMN updated datetime(6) DEFAULT NULL', 'DO 0'); PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- ---- align stale baseline column TYPES to the entity (money FLOAT->DECIMAL slice 23; purchase.updated DATE->DATETIME).
-- These columns predate the baseline with old types; MODIFY is idempotent (same type on dev = no-op; converts on fresh).
ALTER TABLE customer_history MODIFY change_amount decimal(19,2);
ALTER TABLE purchase MODIFY updated datetime(6);
ALTER TABLE stock MODIFY batch_purchase_discount decimal(19,2), MODIFY batch_sale_discount decimal(19,2);

SET FOREIGN_KEY_CHECKS=1;
