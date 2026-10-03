-- MKT-1d — platform-wide marketplace settings the operator chooses (source §7.4 "ranking defaults", MKT-R7.4).
-- slice: docs/slices/mkt-1d-public-catalogue.md
--
-- One row per key. common-settings is per TENANT and cannot hold a rule that belongs to no tenant, so the
-- marketplace keeps its own small table. Phase 1 key:
--   public.defaultSort   RECOMMENDED | LOWEST_PRICE | FASTEST | WARRANTY | RETURN_POLICY   (absent ⇒ RECOMMENDED)
--
-- VARCHAR values (never MySQL ENUM), no JSON/TEXT. Idempotent (D7).
CREATE TABLE IF NOT EXISTS mkt_platform_setting (
  setting_key         VARCHAR(64)   NOT NULL,
  setting_value       VARCHAR(255)  NOT NULL,
  updated_by_user_id  BIGINT        DEFAULT NULL,
  updated_at          DATETIME(6)   DEFAULT NULL,
  version             INT           NOT NULL DEFAULT 0,
  PRIMARY KEY (setting_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
