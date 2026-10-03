-- MKT-1c — offers, the policies they carry, and the published projection customers read (source §3, §4.1, §5.3,
-- §7.4–7.5, §9.2–9.3, §14; MKT-R5.3 …). Design: microservices/docs/marketplace-multiseller-design.md ·
-- slice: docs/slices/mkt-1c-offers.md
--
-- VARCHAR statuses (never MySQL ENUM), DECIMAL(19,2) money, no JSON/TEXT columns. Idempotent (D7).

-- ── 1. Operator price limits on a canonical product (source §7.4 "price floor, where required") ─────────
SET @sql := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='mkt_product' AND COLUMN_NAME='price_floor')=0,
    'ALTER TABLE mkt_product ADD COLUMN price_floor DECIMAL(19,2) NULL AFTER category_name', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='mkt_product' AND COLUMN_NAME='price_ceiling')=0,
    'ALTER TABLE mkt_product ADD COLUMN price_ceiling DECIMAL(19,2) NULL AFTER price_floor', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ── 2. Policies: warranty, returns, commission — written by the operator, chosen by sellers ───────────────
--
-- APPEND-ONLY by design: a policy is never edited, only deactivated and replaced, so an offer (and in MKT-1e an
-- order snapshot) always points at the exact terms it was sold under. One table, typed by policy_type; the
-- columns of the other types stay NULL.
--
--   WARRANTY   provider, months, starts (DELIVERY), covers, excludes, claim process — source §14. The provider is
--              never assumed to be MaxTheService (MKT-R14.2).
--   RETURN     return_days. Who pays is decided per CAUSE at return time (ReturnCostPolicy, MKT-1f), not here.
--   COMMISSION basis ITEMS | ITEMS_PLUS_DELIVERY | FIXED with rate or fixed amount (CommissionPolicy, MKT-1a).
--              is_default marks the one applied to new offers until per-category rules exist.
CREATE TABLE IF NOT EXISTS mkt_policy (
  id                  BIGINT        NOT NULL AUTO_INCREMENT,
  policy_type         VARCHAR(16)   NOT NULL,
  name                VARCHAR(120)  NOT NULL,
  active              BIT(1)        NOT NULL,
  is_default          BIT(1)        NOT NULL,
  warranty_provider   VARCHAR(120)  DEFAULT NULL,
  warranty_months     INT           DEFAULT NULL,
  warranty_starts     VARCHAR(16)   DEFAULT NULL,
  warranty_covers     VARCHAR(300)  DEFAULT NULL,
  warranty_excludes   VARCHAR(300)  DEFAULT NULL,
  claim_process       VARCHAR(300)  DEFAULT NULL,
  return_days         INT           DEFAULT NULL,
  commission_basis    VARCHAR(24)   DEFAULT NULL,
  commission_rate     DECIMAL(9,6)  DEFAULT NULL,
  commission_fixed    DECIMAL(19,2) DEFAULT NULL,
  created_by_user_id  BIGINT        DEFAULT NULL,
  created_at          DATETIME(6)   DEFAULT NULL,
  PRIMARY KEY (id),
  KEY idx_mkt_policy_type_active (policy_type, active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ── 3. The offer: one seller's terms for one canonical product ───────────────────────────────────────────
--
-- organization_id is the SELLER (JWT). The four party columns are stamped by the server; in Phase 1 they are all
-- the seller (MERCHANT stock, source §4.1). They exist as columns now so PLATFORM/SUPPLIER/CONSIGNMENT (Phases 3–5)
-- change who is written into them, not the schema — and so no code ever has to ASSUME seller = owner = fulfiller
-- (source §3, MKT-R3.2).
--
-- One offer per seller per product in Phase 1 (UNIQUE). There is deliberately NO stock column: availability lives
-- in the projection, refreshed from inventory, and the reservation at checkout is the authority.
CREATE TABLE IF NOT EXISTS mkt_offer (
  id                          BIGINT        NOT NULL AUTO_INCREMENT,
  organization_id             BIGINT        NOT NULL,
  mkt_product_id              BIGINT        NOT NULL,
  source_product_id           BIGINT        NOT NULL,
  stock_source_type           VARCHAR(16)   NOT NULL,
  stock_owner_organization_id BIGINT        NOT NULL,
  custodian_organization_id   BIGINT        NOT NULL,
  seller_organization_id      BIGINT        NOT NULL,
  fulfiller_organization_id   BIGINT        NOT NULL,
  seller_sku                  VARCHAR(64)   DEFAULT NULL,
  list_price                  DECIMAL(19,2) DEFAULT NULL,
  marketplace_price           DECIMAL(19,2) NOT NULL,
  delivery_areas              VARCHAR(500)  NOT NULL,
  promise_hours               INT           NOT NULL,
  warranty_policy_id          BIGINT        DEFAULT NULL,
  return_policy_id            BIGINT        DEFAULT NULL,
  commission_policy_id        BIGINT        DEFAULT NULL,
  approval_status             VARCHAR(16)   NOT NULL,
  paused                      BIT(1)        NOT NULL,
  review_note                 VARCHAR(500)  DEFAULT NULL,
  reviewed_by_user_id         BIGINT        DEFAULT NULL,
  reviewed_at                 DATETIME(6)   DEFAULT NULL,
  published_at                DATETIME(6)   DEFAULT NULL,
  version                     INT           NOT NULL DEFAULT 0,
  created_at                  DATETIME(6)   DEFAULT NULL,
  updated_at                  DATETIME(6)   DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_mkt_offer_org_product (organization_id, mkt_product_id),
  KEY idx_mkt_offer_org_created (organization_id, created_at),
  KEY idx_mkt_offer_status_created (approval_status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ── 4. The published projection: the ONLY cross-tenant read on the marketplace ───────────────────────────
--
-- Source §9.2 lists what may be shared; this table holds exactly that and nothing else. Supplier cost, margin,
-- purchase rates and stock history (§9.3) are STRUCTURALLY ABSENT — there is no column for them to leak from.
-- Customers read only status = 'LIVE'. last_sync_at is shown and drives staleness (an offer whose stock was not
-- confirmed recently is not ranked: OfferEligibility). Ruling R-MKT-4: a table, not Redis.
CREATE TABLE IF NOT EXISTS mkt_offer_projection (
  offer_id               BIGINT        NOT NULL,
  mkt_product_id         BIGINT        NOT NULL,
  seller_organization_id BIGINT        NOT NULL,
  seller_display_name    VARCHAR(120)  NOT NULL,
  stock_source_type      VARCHAR(16)   NOT NULL,
  regulated_status       VARCHAR(16)   NOT NULL,
  price                  DECIMAL(19,2) NOT NULL,
  available_qty          DECIMAL(19,4) DEFAULT NULL,
  delivery_areas         VARCHAR(500)  NOT NULL,
  promise_hours          INT           NOT NULL,
  warranty_months        INT           DEFAULT NULL,
  warranty_provider      VARCHAR(120)  DEFAULT NULL,
  warranty_starts        VARCHAR(16)   DEFAULT NULL,
  warranty_covers        VARCHAR(300)  DEFAULT NULL,
  warranty_excludes      VARCHAR(300)  DEFAULT NULL,
  return_days            INT           DEFAULT NULL,
  status                 VARCHAR(12)   NOT NULL,
  last_sync_at           DATETIME(6)   DEFAULT NULL,
  updated_at             DATETIME(6)   DEFAULT NULL,
  PRIMARY KEY (offer_id),
  -- the public read: "LIVE offers of this product, cheapest first"
  KEY idx_mkt_proj_product_status_price (mkt_product_id, status, price),
  -- seller suspension takes every row of one seller down in one statement
  KEY idx_mkt_proj_seller (seller_organization_id),
  -- the stock refresher: "LIVE rows confirmed longest ago"
  KEY idx_mkt_proj_status_sync (status, last_sync_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
