-- Flyway: coupons / promotions (slice 72, E13). Idempotent — CREATE TABLE IF NOT EXISTS + information_schema-guarded
-- ADD COLUMN. DDL mirrors the Hibernate-generated schema so `validate` accepts it.
SET FOREIGN_KEY_CHECKS=0;

CREATE TABLE IF NOT EXISTS coupon (
  id bigint NOT NULL AUTO_INCREMENT,
  organization_id bigint DEFAULT NULL,
  code varchar(255) DEFAULT NULL,
  type varchar(255) DEFAULT NULL,
  value decimal(19,2) DEFAULT NULL,
  min_spend decimal(19,2) DEFAULT NULL,
  active bit(1) DEFAULT NULL,
  starts_at datetime(6) DEFAULT NULL,
  ends_at datetime(6) DEFAULT NULL,
  usage_limit int DEFAULT NULL,
  used_count int DEFAULT NULL,
  created_at datetime(6) DEFAULT NULL,
  updated_at datetime(6) DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_coupon_org_code (organization_id, code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='orders' AND COLUMN_NAME='coupon_code')=0,
  'ALTER TABLE orders ADD COLUMN coupon_code varchar(255) DEFAULT NULL', 'DO 0'); PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='orders' AND COLUMN_NAME='discount_amount')=0,
  'ALTER TABLE orders ADD COLUMN discount_amount decimal(19,2) DEFAULT NULL', 'DO 0'); PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET FOREIGN_KEY_CHECKS=1;
