-- Flyway: persistent storefront cart (slice 68, E3). Idempotent (CREATE TABLE IF NOT EXISTS) — no-op where dev
-- ddl-auto already made the tables, full create on a fresh/prod DB. DDL mirrors the Hibernate-generated schema so
-- `validate` accepts it. cart is created before cart_item (FK target).
SET FOREIGN_KEY_CHECKS=0;

CREATE TABLE IF NOT EXISTS cart (
  id bigint NOT NULL AUTO_INCREMENT,
  organization_id bigint DEFAULT NULL,
  cart_token varchar(255) DEFAULT NULL,
  customer_account_id bigint DEFAULT NULL,
  status varchar(255) DEFAULT NULL,
  created_at datetime(6) DEFAULT NULL,
  updated_at datetime(6) DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_cart_org_token (organization_id, cart_token),
  KEY idx_cart_account (customer_account_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS cart_item (
  id bigint NOT NULL AUTO_INCREMENT,
  product_id bigint DEFAULT NULL,
  product_name varchar(255) DEFAULT NULL,
  unit_price decimal(19,2) DEFAULT NULL,
  quantity int DEFAULT NULL,
  cart_ref bigint DEFAULT NULL,
  PRIMARY KEY (id),
  KEY idx_cart_item_cart (cart_ref),
  CONSTRAINT fk_cart_item_cart FOREIGN KEY (cart_ref) REFERENCES cart (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

SET FOREIGN_KEY_CHECKS=1;
