-- MKT-5a — consignment agreements (slice doc docs/slices/mkt-5a-consignment-agreements.md). R4.6: every term of a
-- consignment is set BEFORE any consigned stock can move. Ruling 2026-10-15 ("Warehouse first"): the consignor is an
-- approved seller, the consignee (holder and seller) is the MaxTheService warehouse.
--
-- A row is never edited after it is offered: new terms are a new agreement, which replaces the old one when the
-- consignor accepts it. VARCHAR statuses (never MySQL ENUM), DECIMAL(5,2) percentages, BIT(1) booleans,
-- ddl-auto=validate. Idempotent (D7).
CREATE TABLE IF NOT EXISTS mkt_consignment_agreement (
  id                         BIGINT        NOT NULL AUTO_INCREMENT,
  consignor_org_id           BIGINT        NOT NULL,                 -- owns the stock until it is sold
  consignee_org_id           BIGINT        NOT NULL,                 -- holds and sells it: the warehouse
  status                     VARCHAR(16)   NOT NULL,                 -- OFFERED, ACTIVE, DECLINED, WITHDRAWN, ENDED, REPLACED
  ownership_transfer         VARCHAR(16)   NOT NULL,                 -- ON_SALE, ON_DELIVERY
  price_set_by               VARCHAR(16)   NOT NULL,                 -- CONSIGNOR, CONSIGNEE
  commission_pct             DECIMAL(5,2)  NOT NULL,                 -- what the consignee keeps of each sale
  settle_every_days          INT           NOT NULL,
  reconcile_every_days       INT           NOT NULL,
  count_every_days           INT           NOT NULL,
  shrinkage_tolerance_pct    DECIMAL(5,2)  NOT NULL,
  damage_bearer              VARCHAR(16)   NOT NULL,                 -- CONSIGNOR, CONSIGNEE
  expiry_bearer              VARCHAR(16)   NOT NULL,
  insured_by                 VARCHAR(16)   NOT NULL,                 -- CONSIGNOR, CONSIGNEE, NONE
  claim_window_days          INT           NOT NULL,
  proof_required             BIT(1)        NOT NULL DEFAULT b'1',
  unsold_return_days         INT           NOT NULL,
  unsold_return_bearer       VARCHAR(16)   NOT NULL,
  note                       VARCHAR(500)  DEFAULT NULL,
  answer_reason              VARCHAR(500)  DEFAULT NULL,           -- why declined or ended
  replaced_by_id             BIGINT        DEFAULT NULL,
  offered_by_user_id         BIGINT        DEFAULT NULL,
  answered_by_user_id        BIGINT        DEFAULT NULL,
  created_at                 DATETIME(6)   NOT NULL,
  answered_at                DATETIME(6)   DEFAULT NULL,           -- accepted, declined or withdrawn
  ended_at                   DATETIME(6)   DEFAULT NULL,
  version                    INT           NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_mkt_consign_pair (consignor_org_id, consignee_org_id, status),
  KEY idx_mkt_consign_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
