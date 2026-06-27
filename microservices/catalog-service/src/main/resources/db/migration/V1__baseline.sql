-- Flyway baseline for catalog-service (P0): catalog had no Flyway, its tables existed only via dev
-- ddl-auto:update, so a fresh prod deploy under ddl-auto:validate would fail. This V1 creates the live
-- Hibernate schema so `validate` passes. IDEMPOTENT (CREATE TABLE IF NOT EXISTS) — a no-op on the existing
-- dev DB, full create on a fresh/prod DB. DDL mirrors the live tables exactly.
-- categories is created first because products.category_id FKs it.
SET FOREIGN_KEY_CHECKS=0;

CREATE TABLE IF NOT EXISTS categories (
  id bigint NOT NULL AUTO_INCREMENT,
  created_at datetime(6) DEFAULT NULL,
  description varchar(255) DEFAULT NULL,
  name varchar(255) NOT NULL,
  organization_id bigint DEFAULT NULL,
  updated_at datetime(6) DEFAULT NULL,
  user_id bigint DEFAULT NULL,
  user_type varchar(255) DEFAULT NULL,
  parent_id bigint DEFAULT NULL,
  PRIMARY KEY (id),
  KEY FKsaok720gsu4u2wrgbk10b5n8d (parent_id),
  CONSTRAINT FKsaok720gsu4u2wrgbk10b5n8d FOREIGN KEY (parent_id) REFERENCES categories (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS products (
  id bigint NOT NULL AUTO_INCREMENT,
  created_at datetime(6) DEFAULT NULL,
  created_by bigint DEFAULT NULL,
  description varchar(2000) DEFAULT NULL,
  image_url varchar(255) DEFAULT NULL,
  is_active bit(1) DEFAULT NULL,
  manufacturer varchar(255) DEFAULT NULL,
  name varchar(255) NOT NULL,
  organization_id bigint DEFAULT NULL,
  selling_price decimal(38,2) DEFAULT NULL,
  sku varchar(255) NOT NULL,
  tax_rate decimal(38,2) DEFAULT NULL,
  unit varchar(255) DEFAULT NULL,
  updated_at datetime(6) DEFAULT NULL,
  user_id bigint DEFAULT NULL,
  user_type varchar(255) DEFAULT NULL,
  category_id bigint DEFAULT NULL,
  PRIMARY KEY (id),
  KEY IDXfhmd06dsmj6k0n90swsh8ie9g (sku),
  KEY FKog2rp4qthbtt2lfyhfo32lsw9 (category_id),
  CONSTRAINT FKog2rp4qthbtt2lfyhfo32lsw9 FOREIGN KEY (category_id) REFERENCES categories (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

SET FOREIGN_KEY_CHECKS=1;
