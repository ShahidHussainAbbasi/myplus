-- Flyway: pharma rebase + clinical schema (slices 41–45), created by ddl-auto in dev — needed on fresh/prod
-- (ddl-auto:validate). The V1 baseline predates the itemId/productId rebase, org-scoping, and the medicine master.
-- All idempotent: CREATE TABLE IF NOT EXISTS + information_schema-guarded ADD COLUMN. DDL mirrors live Hibernate
-- schema so `validate` accepts it.
SET FOREIGN_KEY_CHECKS=0;

CREATE TABLE IF NOT EXISTS medicine_profile (
  id bigint NOT NULL AUTO_INCREMENT,
  brand_name varchar(255) DEFAULT NULL,
  contraindications varchar(1000) DEFAULT NULL,
  controlled_substance bit(1) DEFAULT NULL,
  created_at datetime(6) DEFAULT NULL,
  drug_category varchar(255) DEFAULT NULL,
  form varchar(255) DEFAULT NULL,
  generic_name varchar(255) DEFAULT NULL,
  is_active bit(1) DEFAULT NULL,
  manufacturer varchar(255) DEFAULT NULL,
  organization_id bigint DEFAULT NULL,
  product_id bigint NOT NULL,
  product_name varchar(255) DEFAULT NULL,
  rx_required bit(1) DEFAULT NULL,
  side_effects varchar(1000) DEFAULT NULL,
  storage_conditions varchar(255) DEFAULT NULL,
  strength varchar(255) DEFAULT NULL,
  unit varchar(255) DEFAULT NULL,
  updated_at datetime(6) DEFAULT NULL,
  user_id bigint DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_medprofile_org_product (organization_id, product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS medicine_clinical (
  id bigint NOT NULL AUTO_INCREMENT,
  controlled_substance bit(1) DEFAULT NULL,
  drug_category varchar(255) DEFAULT NULL,
  item_id bigint NOT NULL,
  medicine_name varchar(255) DEFAULT NULL,
  organization_id bigint DEFAULT NULL,
  rx_required bit(1) DEFAULT NULL,
  updated datetime(6) DEFAULT NULL,
  user_id bigint DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_medclinical_org_item (organization_id, item_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Guarded column adds (slice 41–45 rebase to itemId + org-scoping) ------------------------------------------------
-- helper pattern: add column only if absent.
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='dispensing' AND COLUMN_NAME='controlled')=0,        'ALTER TABLE dispensing ADD COLUMN controlled bit(1) DEFAULT NULL', 'DO 0');                 PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='dispensing' AND COLUMN_NAME='invoice_no')=0,        'ALTER TABLE dispensing ADD COLUMN invoice_no varchar(255) DEFAULT NULL', 'DO 0');           PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='dispensing' AND COLUMN_NAME='item_id')=0,           'ALTER TABLE dispensing ADD COLUMN item_id bigint DEFAULT NULL', 'DO 0');                    PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='dispensing' AND COLUMN_NAME='medicine_name')=0,     'ALTER TABLE dispensing ADD COLUMN medicine_name varchar(255) DEFAULT NULL', 'DO 0');        PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='dispensing' AND COLUMN_NAME='organization_id')=0,   'ALTER TABLE dispensing ADD COLUMN organization_id bigint DEFAULT NULL', 'DO 0');            PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='dispensing' AND COLUMN_NAME='product_id')=0,        'ALTER TABLE dispensing ADD COLUMN product_id bigint DEFAULT NULL', 'DO 0');                 PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='drug_interactions' AND COLUMN_NAME='item_id1')=0,        'ALTER TABLE drug_interactions ADD COLUMN item_id1 bigint DEFAULT NULL', 'DO 0');        PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='drug_interactions' AND COLUMN_NAME='item_id2')=0,        'ALTER TABLE drug_interactions ADD COLUMN item_id2 bigint DEFAULT NULL', 'DO 0');        PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='drug_interactions' AND COLUMN_NAME='organization_id')=0, 'ALTER TABLE drug_interactions ADD COLUMN organization_id bigint DEFAULT NULL', 'DO 0'); PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='drug_interactions' AND COLUMN_NAME='product_id1')=0,     'ALTER TABLE drug_interactions ADD COLUMN product_id1 bigint DEFAULT NULL', 'DO 0');     PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='drug_interactions' AND COLUMN_NAME='product_id2')=0,     'ALTER TABLE drug_interactions ADD COLUMN product_id2 bigint DEFAULT NULL', 'DO 0');     PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='drug_interactions' AND COLUMN_NAME='user_id')=0,         'ALTER TABLE drug_interactions ADD COLUMN user_id bigint DEFAULT NULL', 'DO 0');         PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='prescription_items' AND COLUMN_NAME='item_id')=0,       'ALTER TABLE prescription_items ADD COLUMN item_id bigint DEFAULT NULL', 'DO 0');         PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='prescription_items' AND COLUMN_NAME='medicine_name')=0, 'ALTER TABLE prescription_items ADD COLUMN medicine_name varchar(255) DEFAULT NULL', 'DO 0'); PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='prescription_items' AND COLUMN_NAME='product_id')=0,    'ALTER TABLE prescription_items ADD COLUMN product_id bigint DEFAULT NULL', 'DO 0');      PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='prescriptions' AND COLUMN_NAME='organization_id')=0,    'ALTER TABLE prescriptions ADD COLUMN organization_id bigint DEFAULT NULL', 'DO 0');     PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET FOREIGN_KEY_CHECKS=1;
