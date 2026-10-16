-- MKT-5e — unsold consigned stock goes back to its owner (slice doc docs/slices/mkt-5e-unsold-returns.md).
--
-- mkt_consigned_return: the owner asks for units back (any time), or the warehouse sends back units held longer than
-- the agreement's unsold-return term. REQUESTED (no longer sold) -> DISPATCHED (the warehouse handed them over, with
-- the carrier's reference and what it cost) -> RECEIVING -> RECEIVED (the owner has them: its hold let go, RETURNED
-- rows in the ledger). CANCELLED by whoever asked, before it is handed over.
--
-- VARCHAR statuses (never MySQL ENUM), DECIMAL(19,2) money, ddl-auto=validate. Idempotent (D7).

CREATE TABLE IF NOT EXISTS mkt_consigned_return (
  id                      BIGINT         NOT NULL AUTO_INCREMENT,
  agreement_id            BIGINT         NOT NULL,                 -- the agreement in force when asked
  consignor_org_id        BIGINT         NOT NULL,                 -- the owner, who gets the units back
  consignee_org_id        BIGINT         NOT NULL,                 -- the warehouse, which sends them
  mkt_product_id          BIGINT         NOT NULL,
  product_name            VARCHAR(255)   NOT NULL,
  units                   INT            NOT NULL,
  reason                  VARCHAR(16)    NOT NULL,                 -- RECALL (the owner asked) or UNSOLD (past the term)
  bearer                  VARCHAR(16)    NOT NULL,                 -- who pays to send it: CONSIGNOR or CONSIGNEE
  cost                    DECIMAL(19,2)  DEFAULT NULL,             -- what sending it cost, given at hand-over
  reference               VARCHAR(100)   DEFAULT NULL,             -- the carrier's reference, given at hand-over
  settlement_ref          VARCHAR(30)    DEFAULT NULL,             -- the owner's settlement row when it bears the cost
  note                    VARCHAR(300)   DEFAULT NULL,
  status                  VARCHAR(16)    NOT NULL,                 -- REQUESTED, DISPATCHED, RECEIVING, RECEIVED, CANCELLED
  requested_by_org_id     BIGINT         NOT NULL,
  idempotency_key         VARCHAR(64)    NOT NULL,
  requested_by_user_id    BIGINT         DEFAULT NULL,
  requested_at            DATETIME(6)    NOT NULL,
  dispatched_by_user_id   BIGINT         DEFAULT NULL,
  dispatched_at           DATETIME(6)    DEFAULT NULL,
  received_by_user_id     BIGINT         DEFAULT NULL,
  received_at             DATETIME(6)    DEFAULT NULL,
  cancelled_at            DATETIME(6)    DEFAULT NULL,
  version                 INT            NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uq_mkt_return_key (requested_by_org_id, idempotency_key),
  KEY idx_mkt_return_pair (consignor_org_id, consignee_org_id, mkt_product_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
