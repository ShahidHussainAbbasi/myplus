-- MKT-0a — the multi-seller marketplace's seller onboarding (source §20 Phase 0/1, MKT-R9.1, MKT-R20.0, MKT-R20.1).
-- Design: microservices/docs/marketplace-multiseller-design.md · slice: docs/slices/mkt-0a-seller-onboarding.md
--
-- Two tables, both new; nothing existing changes (live-modules rule).
--
-- STATUS IS VARCHAR, NOT A MySQL ENUM, mapped to a String on the entity (the expense-service V1 recipe). A real
-- ENUM here would need an ALTER … MODIFY for every new constant and fails at runtime with "Data truncated" when
-- one is missed — the fulfilment_status lesson (V7 → V15 → V16) this service has already paid for.
--
-- Idempotent and re-runnable (standard D7).

-- ── 1. The seller account: MaxTheService's decision about one business ─────────────────────────────────────
--
-- One row per seller organization (UNIQUE), because a business is a seller once. organization_id is the SELLER'S
-- org: the tenant reads its own row by the JWT org; only the platform operator reads across rows.
--
--   status        PENDING_APPROVAL | APPROVED | REJECTED | SUSPENDED — MarketplaceStateMachines.SELLER_ACCOUNT
--   display_name  what customers will see as the seller; given by the owner at application, never inferred
--   status_reason the operator's sentence for REJECTED/SUSPENDED, shown to the seller as written
--   applied_* / decided_*  stamped, so "who approved this seller" is answerable after staff change
CREATE TABLE IF NOT EXISTS mkt_seller_account (
  id                  BIGINT        NOT NULL AUTO_INCREMENT,
  organization_id     BIGINT        NOT NULL,
  display_name        VARCHAR(120)  NOT NULL,
  status              VARCHAR(24)   NOT NULL,
  status_reason       VARCHAR(500)  DEFAULT NULL,
  applied_by_user_id  BIGINT        DEFAULT NULL,
  applied_at          DATETIME(6)   DEFAULT NULL,
  decided_by_user_id  BIGINT        DEFAULT NULL,
  decided_at          DATETIME(6)   DEFAULT NULL,
  version             INT           NOT NULL DEFAULT 0,
  created_at          DATETIME(6)   DEFAULT NULL,
  updated_at          DATETIME(6)   DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_mkt_seller_org (organization_id),
  -- the operator's queue: "pending applications, oldest first" (D3b — index the predicate the query runs)
  KEY idx_mkt_seller_status_applied (status, applied_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ── 2. Agreement acceptance: who accepted which version, when ──────────────────────────────────────────────
--
-- Source §9: data use is a written data-sharing agreement, never "consignment". A row per (org, agreement,
-- version): accepting v2 later adds a row and keeps v1's, so what a seller agreed to at the time of any order
-- stays provable. Re-accepting the same version is a no-op (UNIQUE), which makes a double click harmless.
CREATE TABLE IF NOT EXISTS mkt_agreement_acceptance (
  id                  BIGINT        NOT NULL AUTO_INCREMENT,
  organization_id     BIGINT        NOT NULL,
  agreement_code      VARCHAR(40)   NOT NULL,
  agreement_version   VARCHAR(20)   NOT NULL,
  accepted_by_user_id BIGINT        DEFAULT NULL,
  accepted_at         DATETIME(6)   DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_mkt_agreement_org_code_ver (organization_id, agreement_code, agreement_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
