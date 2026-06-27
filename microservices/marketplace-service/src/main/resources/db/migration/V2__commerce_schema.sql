-- Flyway: marketplace commerce schema (slices 46–61). V1 baseline was empty (these tables were created by
-- ddl-auto:update in dev), so on a fresh/prod DB (ddl-auto:validate) Flyway must create them. CREATE TABLE IF NOT
-- EXISTS is idempotent: a no-op on the existing dev DB, full create on fresh. DDL mirrors the live Hibernate-generated
-- schema exactly so `validate` accepts it. FK checks off so table order is irrelevant on a fresh DB.
SET FOREIGN_KEY_CHECKS=0;

CREATE TABLE IF NOT EXISTS orders (
  id bigint NOT NULL AUTO_INCREMENT,
  created_at datetime(6) DEFAULT NULL,
  customer_name varchar(255) DEFAULT NULL,
  fulfilment_status enum('CANCELLED','DELIVERED','NEW','PACKED','SHIPPED') DEFAULT NULL,
  invoice_no varchar(255) DEFAULT NULL,
  organization_id bigint DEFAULT NULL,
  shipping_address varchar(255) DEFAULT NULL,
  total decimal(19,2) DEFAULT NULL,
  updated_at datetime(6) DEFAULT NULL,
  user_id bigint DEFAULT NULL,
  customer_contact varchar(255) DEFAULT NULL,
  payment_mode varchar(255) DEFAULT NULL,
  source varchar(255) DEFAULT NULL,
  payment_ref varchar(255) DEFAULT NULL,
  payment_status varchar(255) DEFAULT NULL,
  reservation_id varchar(255) DEFAULT NULL,
  reservation_status varchar(255) DEFAULT NULL,
  customer_account_id bigint DEFAULT NULL,
  PRIMARY KEY (id),
  KEY idx_order_org (organization_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS order_items (
  id bigint NOT NULL AUTO_INCREMENT,
  price decimal(19,2) DEFAULT NULL,
  product_id bigint DEFAULT NULL,
  quantity int DEFAULT NULL,
  order_id bigint DEFAULT NULL,
  PRIMARY KEY (id),
  KEY idx_order_item_order (order_id),
  CONSTRAINT fk_order_item_order FOREIGN KEY (order_id) REFERENCES orders (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS order_events (
  id bigint NOT NULL AUTO_INCREMENT,
  channel varchar(255) DEFAULT NULL,
  created_at datetime(6) DEFAULT NULL,
  note varchar(255) DEFAULT NULL,
  order_id bigint DEFAULT NULL,
  recipient varchar(255) DEFAULT NULL,
  status varchar(255) DEFAULT NULL,
  PRIMARY KEY (id),
  KEY idx_order_event_order (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS storefront_customer (
  id bigint NOT NULL AUTO_INCREMENT,
  created_at datetime(6) DEFAULT NULL,
  email varchar(255) DEFAULT NULL,
  name varchar(255) DEFAULT NULL,
  organization_id bigint DEFAULT NULL,
  password_hash varchar(255) DEFAULT NULL,
  session_token varchar(255) DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_sfc_org_email (organization_id, email),
  KEY idx_sfc_token (session_token)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

SET FOREIGN_KEY_CHECKS=1;
