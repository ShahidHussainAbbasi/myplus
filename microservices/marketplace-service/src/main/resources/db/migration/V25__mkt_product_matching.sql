-- MKT-1b — canonical marketplace products and the match review that feeds them (source §5, §6: MKT-R5.1, R5.2,
-- R6.1–R6.6). Design: microservices/docs/marketplace-multiseller-design.md · slice: docs/slices/mkt-1b-product-matching.md
--
-- Identity attributes are COLUMNS, not a JSON/TEXT blob: they are what the key is built from and what an operator
-- compares side by side, and a @Lob/TEXT column is the type mismatch that crash-looped two services under
-- ddl-auto=validate. Statuses are VARCHAR (the V24 / expense-service recipe), never a MySQL ENUM.
--
-- Idempotent and re-runnable (standard D7).

-- ── 1. The canonical product: what the CUSTOMER sees, one row per real-world product ───────────────────────
--
-- Deliberately NO organization_id. A canonical product belongs to no tenant: it is MaxTheService's catalogue entry,
-- created and corrected only by the platform operator. Sellers reach it only through their own mkt_product_source
-- rows, which ARE tenant-scoped. identity_key is UNIQUE: the same composite key (or GTIN) can never become two
-- canonical products, and that constraint, not a read-then-write check, is what makes it true under concurrency.
CREATE TABLE IF NOT EXISTS mkt_product (
  id                 BIGINT        NOT NULL AUTO_INCREMENT,
  identity_key       VARCHAR(400)  NOT NULL,
  canonical_name     VARCHAR(255)  NOT NULL,
  brand              VARCHAR(80)   NOT NULL,
  model              VARCHAR(120)  NOT NULL,
  variant            VARCHAR(80)   DEFAULT NULL,
  colour             VARCHAR(60)   DEFAULT NULL,
  size               VARCHAR(60)   DEFAULT NULL,
  unit               VARCHAR(40)   DEFAULT NULL,
  pack_size          VARCHAR(40)   DEFAULT NULL,
  condition_grade    VARCHAR(40)   DEFAULT NULL,
  warranty_type      VARCHAR(60)   DEFAULT NULL,
  gtin               VARCHAR(14)   DEFAULT NULL,
  category_name      VARCHAR(120)  DEFAULT NULL,
  regulated_status   VARCHAR(16)   NOT NULL,
  approval_status    VARCHAR(16)   NOT NULL,
  created_by_user_id BIGINT        DEFAULT NULL,
  version            INT           NOT NULL DEFAULT 0,
  created_at         DATETIME(6)   DEFAULT NULL,
  updated_at         DATETIME(6)   DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_mkt_product_identity (identity_key),
  KEY idx_mkt_product_name (canonical_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ── 2. A seller's proposal: "this product of MINE is that marketplace product" ─────────────────────────────
--
-- One row per (seller org, seller's catalog product) — UNIQUE — so a re-proposal after a correction updates the
-- same row and keeps one history, rather than leaving abandoned duplicates in the operator's queue.
--
--   source_product_name  SNAPSHOT of the seller's own product name at proposal time (catalog owns the live one)
--   proposed_*           what the SELLER declared; the operator reviews it. Never trusted to merge on its own.
--   source_regulated     read from catalog (rxRequired / controlledSubstance) at proposal time — never the client
--   suggested_product_id an existing canonical product with the same key: a SUGGESTION shown to the operator.
--                        Source §6: never auto-merged.
--   mkt_product_id       set only when the operator MATCHES; cleared when the match is corrected or rejected
CREATE TABLE IF NOT EXISTS mkt_product_source (
  id                   BIGINT        NOT NULL AUTO_INCREMENT,
  organization_id      BIGINT        NOT NULL,
  source_product_id    BIGINT        NOT NULL,
  source_product_name  VARCHAR(255)  DEFAULT NULL,
  proposed_identity_key VARCHAR(400) NOT NULL,
  brand                VARCHAR(80)   NOT NULL,
  model                VARCHAR(120)  NOT NULL,
  variant              VARCHAR(80)   DEFAULT NULL,
  colour               VARCHAR(60)   DEFAULT NULL,
  size                 VARCHAR(60)   DEFAULT NULL,
  unit                 VARCHAR(40)   DEFAULT NULL,
  pack_size            VARCHAR(40)   DEFAULT NULL,
  condition_grade      VARCHAR(40)   DEFAULT NULL,
  warranty_type        VARCHAR(60)   DEFAULT NULL,
  gtin                 VARCHAR(14)   DEFAULT NULL,
  source_regulated     VARCHAR(16)   NOT NULL,
  match_status         VARCHAR(20)   NOT NULL,
  suggested_product_id BIGINT        DEFAULT NULL,
  mkt_product_id       BIGINT        DEFAULT NULL,
  review_note          VARCHAR(500)  DEFAULT NULL,
  proposed_by_user_id  BIGINT        DEFAULT NULL,
  reviewed_by_user_id  BIGINT        DEFAULT NULL,
  reviewed_at          DATETIME(6)   DEFAULT NULL,
  version              INT           NOT NULL DEFAULT 0,
  created_at           DATETIME(6)   DEFAULT NULL,
  updated_at           DATETIME(6)   DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_mkt_source_org_product (organization_id, source_product_id),
  -- the operator's queue: "waiting for review, oldest first"
  KEY idx_mkt_source_status_created (match_status, created_at),
  -- the seller's own list, newest first
  KEY idx_mkt_source_org_created (organization_id, created_at),
  -- "which canonical product does this key already have?" (suggestions) and "which sellers source it?"
  KEY idx_mkt_source_key (proposed_identity_key),
  KEY idx_mkt_source_product (mkt_product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
